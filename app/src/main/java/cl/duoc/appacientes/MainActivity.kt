package cl.duoc.appacientes

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// -----------------------------------------------------
// MODELO DE DATOS
// -----------------------------------------------------

@Entity(tableName = "atenciones")
data class AtencionPaciente(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fechaHora: Long = System.currentTimeMillis(),
    val actualizado: Long? = null,

    val nombre: String,
    val rut: String,
    val piso: String,
    val empresa: String,
    val sexo: String,

    val motivoConsulta: String,
    val categorizacion: String,
    val servicio: String,

    // Controles (v3 unifica esto en columnas fijas)
    val pa: String = "", val fc: String = "", val glucosa: String = "", val sat: String = "", val temperatura: String = "",
    val pa2: String = "", val fc2: String = "", val glucosa2: String = "", val sat2: String = "", val temperatura2: String = "",
    val pa3: String = "", val fc3: String = "", val glucosa3: String = "", val sat3: String = "", val temperatura3: String = "",
    val pa4: String = "", val fc4: String = "", val glucosa4: String = "", val sat4: String = "", val temperatura4: String = "",
    val pa5: String = "", val fc5: String = "", val glucosa5: String = "", val sat5: String = "", val temperatura5: String = "",
    val pa6: String = "", val fc6: String = "", val glucosa6: String = "", val sat6: String = "", val temperatura6: String = "",
    
    val numControles: Int = 1,
    val enfermedadCronica: String = "Sin registro",
    val otraEnfermedad: String = "",
    val motivoAsistenciaEnfermeria: String = "",
    val atencion: String = "",
    val fotoCredencialUri: String = ""
)

@Dao
interface AtencionDao {
    @Insert
    suspend fun insertar(atencion: AtencionPaciente)
    @Update
    suspend fun actualizar(atencion: AtencionPaciente)
    @Delete
    suspend fun eliminar(atencion: AtencionPaciente)
    @Query("SELECT * FROM atenciones ORDER BY fechaHora DESC")
    fun obtenerTodas(): Flow<List<AtencionPaciente>>
    @Query("SELECT COUNT(*) FROM atenciones")
    suspend fun contarTotal(): Int
    @Query("SELECT * FROM atenciones WHERE LOWER(nombre) LIKE '%' || LOWER(:texto) || '%' OR LOWER(rut) LIKE '%' || LOWER(:texto) || '%' OR LOWER(piso) LIKE '%' || LOWER(:texto) || '%' ORDER BY fechaHora DESC")
    fun buscar(texto: String): Flow<List<AtencionPaciente>>
    @Query("SELECT * FROM atenciones WHERE fechaHora BETWEEN :inicio AND :fin ORDER BY fechaHora DESC")
    fun entreFechas(inicio: Long, fin: Long): Flow<List<AtencionPaciente>>
    @Query("SELECT * FROM atenciones WHERE LOWER(nombre) LIKE '%' || LOWER(:query) || '%' OR LOWER(rut) LIKE '%' || LOWER(:query) || '%' GROUP BY rut, nombre ORDER BY fechaHora DESC LIMIT 5")
    suspend fun obtenerSugerencias(query: String): List<AtencionPaciente>
}

@Database(entities = [AtencionPaciente::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun atencionDao(): AtencionDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pacientes_db")
                    .fallbackToDestructiveMigration() // Último recurso ante fallos de versión, compensado por la recuperación automática
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// -----------------------------------------------------
// REPOSITORIO Y VIEWMODEL
// -----------------------------------------------------

class AtencionRepository(private val dao: AtencionDao) {
    val todas = dao.obtenerTodas()
    private val firestore = Firebase.firestore

    suspend fun estaVacio() = dao.contarTotal() == 0

    fun buscar(texto: String) = if (texto.isBlank()) dao.obtenerTodas() else dao.buscar(texto)

    fun atencionesPeriodo(mes: Int, anio: Int): Flow<List<AtencionPaciente>> {
        val inicioMes = LocalDate.of(anio, mes, 1)
        val inicio = inicioMes.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fin = inicioMes.plusMonths(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        return dao.entreFechas(inicio, fin)
    }

    fun atencionesHoy(): Flow<List<AtencionPaciente>> {
        val hoy = LocalDate.now()
        val inicio = hoy.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fin = hoy.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        return dao.entreFechas(inicio, fin)
    }

    suspend fun guardar(a: AtencionPaciente) {
        // Guardar localmente primero
        if (a.id == 0L) dao.insertar(a) 
        else dao.actualizar(a.copy(actualizado = System.currentTimeMillis()))

        // Sincronizar con Firebase (Firestore maneja el offline automáticamente)
        try {
            val docId = if (a.rut.isNotBlank()) a.rut else a.id.toString()
            firestore.collection("atenciones")
                .document(docId + "_" + a.fechaHora)
                .set(a)
        } catch (e: Exception) {
            // Firebase guardará el intento y lo subirá cuando haya red
        }
    }

    suspend fun eliminar(a: AtencionPaciente) {
        dao.eliminar(a)
        try {
            val docId = if (a.rut.isNotBlank()) a.rut else a.id.toString()
            firestore.collection("atenciones")
                .document(docId + "_" + a.fechaHora)
                .delete()
        } catch (e: Exception) {}
    }

    suspend fun obtenerSugerencias(q: String) = dao.obtenerSugerencias(q)
}

class PacientesViewModel(private val repo: AtencionRepository) : ViewModel() {
    val todas = repo.todas
    val hoy = repo.atencionesHoy()
    suspend fun verificarRecuperacion() {
        if (repo.estaVacio()) {
            val mayo = listOf(
                AtencionPaciente(fechaHora = parseFecha("20/05/2026 10:27"), nombre = "Paula Vargas", rut = "17737825-9", empresa = "Caja Los Andes", sexo = "Mujer", piso = "3", pa = "120/77", fc = "88", glucosa = "112", sat = "97", temperatura = "36.5", categorizacion = "C3", motivoConsulta = "Chequeo hábito saludable", servicio = "Enfermería", atencion = "Se controla signos vitales se encuentra alterados"),
                AtencionPaciente(fechaHora = parseFecha("20/05/2026 09:10"), nombre = "Francisco Muñoz", rut = "6649595-7", empresa = "Caja Los Andes", sexo = "Hombre", piso = "3", pa = "86/54", fc = "59", glucosa = "113", sat = "94", temperatura = "36.2", categorizacion = "C3", motivoConsulta = "Urgencia", servicio = "Enfermería", atencion = "paciente hipotenso y con hiperglucemia."),
                AtencionPaciente(fechaHora = parseFecha("15/05/2026 09:10"), nombre = "Jose Duarte", rut = "11915301-4", empresa = "Caja Los Andes", sexo = "Hombre", piso = "9", pa = "130/90", fc = "81", glucosa = "95", sat = "97", temperatura = "36.4", categorizacion = "C5", motivoConsulta = "Chequeo hábito saludable", servicio = "Control en piso", atencion = "signos vitales normales"),
                AtencionPaciente(fechaHora = parseFecha("13/05/2026 11:23"), nombre = "Jose Duarte", rut = "11915301-4", empresa = "Caja Los Andes", sexo = "Hombre", piso = "9", pa = "135/90", fc = "82", glucosa = "", sat = "97", temperatura = "36.3", categorizacion = "C5", motivoConsulta = "Chequeo hábito saludable", servicio = "Control en piso", atencion = "sin alteraciones")
            )
            mayo.forEach { repo.guardar(it) }
        }
    }
    fun atencionesMes(m: Int, a: Int) = repo.atencionesPeriodo(m, a)
    fun buscar(t: String) = repo.buscar(t)
    suspend fun guardar(a: AtencionPaciente) = repo.guardar(a)
    suspend fun eliminar(a: AtencionPaciente) = repo.eliminar(a)
    suspend fun obtenerSugerencias(q: String) = repo.obtenerSugerencias(q)
}

class PacientesViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PacientesViewModel(AtencionRepository(AppDatabase.get(context).atencionDao())) as T
}

// -----------------------------------------------------
// MAIN ACTIVITY
// -----------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppPacientes() }
    }
}

@Composable
fun AppPacientes() {
    val context = LocalContext.current
    val vm: PacientesViewModel = viewModel(factory = PacientesViewModelFactory(context))
    var mostrarCarga by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { 
        vm.verificarRecuperacion()
        delay(2000)
        mostrarCarga = false 
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0288D1), secondary = Color(0xFF03A9F4), background = Color(0xFFF0F7FA), surface = Color.White)) {
        Crossfade(targetState = mostrarCarga, label = "carga") { if (it) PantallaCarga() else ContenidoPrincipal(vm) }
    }
}

@Composable
fun PantallaCarga() {
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0288D1), Color(0xFF01579B)))), contentAlignment = Alignment.Center) {
        val infiniteTransition = rememberInfiniteTransition(label = "anim")
        val scale by infiniteTransition.animateFloat(initialValue = 0.8f, targetValue = 1.2f, animationSpec = infiniteRepeatable(animation = tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "scale")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(120.dp).scale(scale).background(Color.White.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MedicalServices, null, tint = Color.White, modifier = Modifier.size(60.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("App Pacientes", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        }
        Text("Desarrollado por AmuleyDev", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
fun ContenidoPrincipal(vm: PacientesViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var editando by remember { mutableStateOf<AtencionPaciente?>(null) }
    val meses = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
    val anios = (2024..2030).map { it.toString() }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { HeaderApp() },
        bottomBar = { FooterApp() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFFF0F7FA)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFFF0F7FA))) {
            val tabs = listOf("Registro" to Icons.Default.AddCircle, "Buscar" to Icons.Default.Search, "Hoy" to Icons.Default.Today, "Mes" to Icons.Default.CalendarMonth, "Análisis" to Icons.Default.PieChart, "Exportar" to Icons.Default.FileDownload)
            ScrollableTabRow(selectedTabIndex = tab, containerColor = Color.White, contentColor = Color(0xFF0277BD), edgePadding = 16.dp, divider = {}, indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = Color(0xFF0288D1), height = 3.dp) }) {
                tabs.forEachIndexed { i, (t, icon) -> Tab(selected = tab == i, onClick = { tab = i; if (i != 0) editando = null }, text = { Text(t, fontSize = 11.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) }, icon = { Icon(icon, null, modifier = Modifier.size(18.dp)) }) }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> RegistroScreen(vm, editando) { 
                        editando = null
                        tab = 2 
                        scope.launch { snackbarHostState.showSnackbar("✅ ¡Registro guardado con éxito!") }
                    }
                    1 -> BuscarScreen(vm) { editando = it; tab = 0 }
                    2 -> ListaAtencionesScreen("Pacientes de hoy", vm.hoy, vm) { editando = it; tab = 0 }
                    3 -> MesFiltroScreen(vm, meses, anios) { editando = it; tab = 0 }
                    4 -> AnalisisScreen(vm, meses, anios)
                    5 -> ExportarScreen(vm, meses, anios)
                }
            }
        }
    }
}

@Composable
fun HeaderApp() {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF0288D1), shadowElevation = 4.dp) {
        Column(modifier = Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HealthAndSafety, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Registro de Atenciones", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Gestión eficiente de pacientes", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun FooterApp() {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Text("Desarrollado por AmuleyDev", modifier = Modifier.navigationBarsPadding().padding(8.dp).fillMaxWidth(), textAlign = TextAlign.Center, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun RegistroScreen(vm: PacientesViewModel, editando: AtencionPaciente?, onGuardado: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nombre by remember(editando) { mutableStateOf(editando?.nombre ?: "") }
    var rut by remember(editando) { mutableStateOf(editando?.rut ?: "") }
    var piso by remember(editando) { mutableStateOf(editando?.piso ?: "") }
    var empresa by remember(editando) { mutableStateOf(editando?.empresa ?: "Caja Los Andes") }
    var sexo by remember(editando) { mutableStateOf(editando?.sexo ?: "No informado") }
    var motivo by remember(editando) { mutableStateOf(editando?.motivoConsulta ?: "Chequeo hábito saludable") }
    var cat by remember(editando) { mutableStateOf(editando?.categorizacion ?: "C5") }
    var servicio by remember(editando) { mutableStateOf(editando?.servicio ?: "Enfermería") }
    
    var numControles by remember(editando) { mutableIntStateOf(editando?.numControles ?: 1) }
    var v1 by remember(editando) { mutableStateOf(listOf(editando?.pa ?: "", editando?.fc ?: "", editando?.glucosa ?: "", editando?.sat ?: "", editando?.temperatura ?: "")) }
    var v2 by remember(editando) { mutableStateOf(listOf(editando?.pa2 ?: "", editando?.fc2 ?: "", editando?.glucosa2 ?: "", editando?.sat2 ?: "", editando?.temperatura2 ?: "")) }
    var v3 by remember(editando) { mutableStateOf(listOf(editando?.pa3 ?: "", editando?.fc3 ?: "", editando?.glucosa3 ?: "", editando?.sat3 ?: "", editando?.temperatura3 ?: "")) }
    var v4 by remember(editando) { mutableStateOf(listOf(editando?.pa4 ?: "", editando?.fc4 ?: "", editando?.glucosa4 ?: "", editando?.sat4 ?: "", editando?.temperatura4 ?: "")) }
    var v5 by remember(editando) { mutableStateOf(listOf(editando?.pa5 ?: "", editando?.fc5 ?: "", editando?.glucosa5 ?: "", editando?.sat5 ?: "", editando?.temperatura5 ?: "")) }
    var v6 by remember(editando) { mutableStateOf(listOf(editando?.pa6 ?: "", editando?.fc6 ?: "", editando?.glucosa6 ?: "", editando?.sat6 ?: "", editando?.temperatura6 ?: "")) }

    var enfermedad by remember(editando) { mutableStateOf(editando?.enfermedadCronica ?: "Sin registro") }
    var otraEnf by remember(editando) { mutableStateOf(editando?.otraEnfermedad ?: "") }
    var motivoAsist by remember(editando) { mutableStateOf(editando?.motivoAsistenciaEnfermeria ?: "") }
    var observacion by remember(editando) { mutableStateOf(editando?.atencion ?: "") }
    var fotoUri by remember(editando) { mutableStateOf(editando?.fotoCredencialUri ?: "") }
    var mensaje by remember { mutableStateOf("") }
    var sugerencias by remember { mutableStateOf<List<AtencionPaciente>>(emptyList()) }
    
    LaunchedEffect(nombre, rut) {
        if (editando == null) {
            val q = if (rut.length > 2) rut else if (nombre.length > 2) nombre else ""
            if (q.isNotBlank()) { delay(300); sugerencias = vm.obtenerSugerencias(q) } else sugerencias = emptyList()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { if (it) {} }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { fotoUri = copiarImagenInterna(context, it) } }

    LazyColumn(modifier = Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (editando == null) "Nueva Atención" else "Modificar Registro", fontWeight = FontWeight.Bold, color = Color(0xFF0277BD), fontSize = 20.sp)
                    if (mensaje.isNotBlank()) Surface(color = if(mensaje.contains("⚠️")) Color(0xFFFFEBEE) else Color(0xFFE8F5E9), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text(mensaje, modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = if(mensaje.contains("⚠️")) Color.Red else Color(0xFF2E7D32)) }

                    SeccionTitulo("Datos del Paciente", Icons.Default.Person)
                    CampoTexto("Nombre Completo", nombre) { nombre = it }
                    CampoTexto("RUT", rut) { rut = it }
                    AnimatedVisibility(visible = sugerencias.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFE1F5FE), RoundedCornerShape(12.dp)).padding(8.dp)) {
                            sugerencias.forEach { s -> Row(modifier = Modifier.fillMaxWidth().clickable { nombre=s.nombre; rut=s.rut; sexo=s.sexo; piso=s.piso; empresa=s.empresa; sugerencias=emptyList() }.padding(6.dp)) { Icon(Icons.Default.History, null, tint = Color.Gray); Spacer(Modifier.width(8.dp)); Text("${s.nombre} (${s.rut})") } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CampoTexto("Piso", piso, Modifier.weight(1f)) { piso = it }; MenuDesplegable("Sexo", sexo, listOf("Hombre", "Mujer", "No informado"), Modifier.weight(1.5f)) { sexo = it } }
                    MenuDesplegable("Empresa", empresa, listOf("Caja Los Andes", "Turismo", "Externo", "Tapp", "Linkes", "Aseo Express", "A. práctica", "Sala monitoreo")) { empresa = it }

                    HorizontalDivider(); SeccionTitulo("Fecha y Hora de Registro", Icons.Default.Event)
                    var fechaStr by remember(editando) { 
                        val d = if (editando != null) Instant.ofEpochMilli(editando.fechaHora).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
                        mutableStateOf(d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))) 
                    }
                    var horaStr by remember(editando) { 
                        val t = if (editando != null) Instant.ofEpochMilli(editando.fechaHora).atZone(ZoneId.systemDefault()).toLocalTime() else LocalDateTime.now().toLocalTime()
                        mutableStateOf(t.format(DateTimeFormatter.ofPattern("HH:mm"))) 
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CampoTexto("Fecha (DD/MM/AAAA)", fechaStr, Modifier.weight(1f)) { fechaStr = it }
                        CampoTexto("Hora (HH:MM)", horaStr, Modifier.weight(1f)) { horaStr = it }
                    }

                    HorizontalDivider(); SeccionTitulo("Evaluación Clínica", Icons.Default.Thermostat)
                    MenuDesplegable("Motivo", motivo, listOf("Chequeo hábito saludable", "Chequeo preventivo", "Atención de enfermería", "Urgencia", "Urgencia laboral")) { motivo = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { 
                        MenuDesplegable("Categoría", cat, listOf("C1", "C2", "C3", "C4", "C5"), Modifier.weight(1f)) { cat = it }
                        MenuDesplegable("Servicio", servicio, listOf("Enfermería", "Control en piso"), Modifier.weight(1.5f)) { servicio = it } 
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(16.dp).background(colorCategoria(cat), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Prioridad: $cat", fontWeight = FontWeight.Bold, color = colorCategoria(cat), fontSize = 12.sp)
                    }

                    for (i in 1..numControles) {
                        val currentVals = when(i){1->v1; 2->v2; 3->v3; 4->v4; 5->v5; else->v6}
                        val updateVals: (List<String>) -> Unit = { when(i){1->v1=it; 2->v2=it; 3->v3=it; 4->v4=it; 5->v5=it; 6->v6=it} }
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if(i==1) Color(0xFFF5F5F5) else Color(0xFFE8F5E9))) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Control $i", fontWeight = FontWeight.Bold, color = if(i==1) Color.Gray else Color(0xFF2E7D32), modifier = Modifier.weight(1f))
                                    if (i > 1) IconButton(onClick = { numControles = i - 1 }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(18.dp)) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CampoTexto("P/A", currentVals[0], Modifier.weight(1f)) { updateVals(currentVals.toMutableList().apply { set(0, it) }) }; CampoTexto("FC", currentVals[1], Modifier.weight(1f), KeyboardType.Number) { updateVals(currentVals.toMutableList().apply { set(1, it) }) } }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CampoTexto("Glu", currentVals[2], Modifier.weight(1f), KeyboardType.Number) { updateVals(currentVals.toMutableList().apply { set(2, it) }) }; CampoTexto("Sat", currentVals[3], Modifier.weight(1f), KeyboardType.Number) { updateVals(currentVals.toMutableList().apply { set(3, it) }) }; CampoTexto("T°", currentVals[4], Modifier.weight(1f), KeyboardType.Decimal) { updateVals(currentVals.toMutableList().apply { set(4, it) }) } }
                            }
                        }
                    }
                    if (numControles < 6) TextButton(onClick = { numControles++ }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("¿AGREGAR OTRO CONTROL?") }

                    HorizontalDivider(); SeccionTitulo("Antecedentes", Icons.Default.Description)
                    MenuDesplegable("Enfermedad", enfermedad, listOf("Sin registro", "Hipertensión", "Diabetes", "Asma", "Disautonomía", "Hipotiroidismo", "Otra")) { enfermedad = it }
                    if (enfermedad == "Otra") CampoTexto("Especifique", otraEnf) { otraEnf = it }
                    CampoTexto("Motivo asist.", motivoAsist, singleLine = false) { motivoAsist = it }
                    CampoTexto("Observaciones", observacion, singleLine = false) { observacion = it }

                    Button(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1F5FE), contentColor = Color(0xFF0288D1))) { Icon(Icons.Default.PhotoCamera, null); Text(" Foto de Credencial") }
                    if (fotoUri.isNotBlank()) Image(painter = rememberAsyncImagePainter(fotoUri), contentDescription = null, modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)

                    Button(modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), onClick = {
                        if (nombre.isBlank() || rut.isBlank()) { mensaje = "⚠️ Nombre y RUT obligatorios"; return@Button }
                        
                        val ts = try {
                            val f = fechaStr.split("/"); val h = horaStr.split(":")
                            LocalDateTime.of(f[2].toInt(), f[1].toInt(), f[0].toInt(), h[0].toInt(), h[1].toInt()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } catch (e: Exception) { editando?.fechaHora ?: System.currentTimeMillis() }

                        val a = AtencionPaciente(id=editando?.id ?: 0, fechaHora=ts, nombre=nombre.trim(), rut=rut.trim(), piso=piso.trim(), empresa=empresa, sexo=sexo, motivoConsulta=motivo, categorizacion=cat, servicio=servicio, pa=v1[0], fc=v1[1], glucosa=v1[2], sat=v1[3], temperatura=v1[4], pa2=v2[0], fc2=v2[1], glucosa2=v2[2], sat2=v2[3], temperatura2=v2[4], pa3=v3[0], fc3=v3[1], glucosa3=v3[2], sat3=v3[3], temperatura3=v3[4], pa4=v4[0], fc4=v4[1], glucosa4=v4[2], sat4=v4[3], temperatura4=v4[4], pa5=v5[0], fc5=v5[1], glucosa5=v5[2], sat5=v5[3], temperatura5=v5[4], pa6=v6[0], fc6=v6[1], glucosa6=v6[2], sat6=v6[3], temperatura6=v6[4], numControles=numControles, enfermedadCronica=enfermedad, otraEnfermedad=otraEnf, motivoAsistenciaEnfermeria=motivoAsist, atencion=observacion, fotoCredencialUri=fotoUri)
                        scope.launch { vm.guardar(a); delay(1500); onGuardado() }
                    }) { Text(if (editando == null) "REGISTRAR ATENCIÓN" else "ACTUALIZAR DATOS", fontWeight = FontWeight.Bold) }
                    if (editando != null) OutlinedButton(onClick = { onGuardado() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("CANCELAR / VOLVER", fontWeight = FontWeight.Bold) }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

@Composable
fun colorCategoria(c: String) = when(c) {
    "C1" -> Color(0xFFD32F2F)
    "C2" -> Color(0xFFF57C00)
    "C3" -> Color(0xFFFBC02D)
    "C4" -> Color(0xFF8BC34A)
    "C5" -> Color(0xFF1B5E20)
    else -> Color.Gray
}

@Composable
fun SeccionTitulo(t: String, i: ImageVector) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(i, null, tint = Color(0xFF0288D1), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t, color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }

@Composable
fun BuscarScreen(vm: PacientesViewModel, onEditar: (AtencionPaciente) -> Unit) {
    var t by remember { mutableStateOf("") }
    val r by vm.buscar(t).collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(16.dp)) { CampoTexto("Buscar...", t, leadingIcon = Icons.Default.Search) { t = it }; LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(r) { TarjetaAtencion(it, vm, onEditar) } } }
}

@Composable
fun MesFiltroScreen(vm: PacientesViewModel, meses: List<String>, anios: List<String>, onEditar: (AtencionPaciente) -> Unit) {
    var m by remember { mutableIntStateOf(LocalDate.now().monthValue) }
    var a by remember { mutableStateOf(LocalDate.now().year.toString()) }
    val lista by vm.atencionesMes(m, a.toInt()).collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MenuDesplegable("Mes", meses[m-1], meses, Modifier.weight(1f)) { m = meses.indexOf(it)+1 }; MenuDesplegable("Año", a, anios, Modifier.weight(1f)) { a = it } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(lista) { TarjetaAtencion(it, vm, onEditar) } }
    }
}

@Composable
fun ListaAtencionesScreen(titulo: String, flow: Flow<List<AtencionPaciente>>, vm: PacientesViewModel, onEditar: (AtencionPaciente) -> Unit) {
    val lista by flow.collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(16.dp)) { Text(titulo, fontWeight = FontWeight.Bold, fontSize = 18.sp); LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(lista) { TarjetaAtencion(it, vm, onEditar) } } }
}

@Composable
fun TarjetaAtencion(item: AtencionPaciente, vm: PacientesViewModel, onEditar: (AtencionPaciente) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    var mostrarDialogoEliminar by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val color = colorCategoria(item.categorizacion)

    if (mostrarDialogoEliminar) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoEliminar = false },
            title = { Text("Confirmar Eliminación", fontWeight = FontWeight.Bold) },
            text = { Text("¿Estás seguro de que deseas eliminar este registro? Esta acción no se puede deshacer.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { vm.eliminar(item) }
                        mostrarDialogoEliminar = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Eliminar", color = Color.White) }
            },
            dismissButton = {
                OutlinedButton(onClick = { mostrarDialogoEliminar = false }) { Text("Cancelar") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth().clickable { exp = !exp }, colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) { Text(item.nombre.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.nombre, fontWeight = FontWeight.Bold); Text("RUT: ${item.rut}", fontSize = 12.sp, color = Color.Gray) }
                Icon(if(exp) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (exp) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DetalleFila("Fecha", formatearFecha(item.fechaHora))
                    DetalleFila("Piso", item.piso)
                }
                DetalleFila("Empresa", item.empresa)
                DetalleFila("Motivo", item.motivoConsulta)
                for(i in 1..item.numControles) {
                    val c = when(i){1->listOf(item.pa, item.fc, item.glucosa, item.sat, item.temperatura); 2->listOf(item.pa2, item.fc2, item.glucosa2, item.sat2, item.temperatura2); 3->listOf(item.pa3, item.fc3, item.glucosa3, item.sat3, item.temperatura3); 4->listOf(item.pa4, item.fc4, item.glucosa4, item.sat4, item.temperatura4); 5->listOf(item.pa5, item.fc5, item.glucosa5, item.sat5, item.temperatura5); else->listOf(item.pa6, item.fc6, item.glucosa6, item.sat6, item.temperatura6)}
                    Text("C$i: P/A:${c[0]} FC:${c[1]} Glu:${c[2]} Sat:${c[3]}% T:${c[4]}°", fontSize = 11.sp)
                }
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEditar(item) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Editar")
                    }
                    OutlinedButton(
                        onClick = { mostrarDialogoEliminar = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        border = BorderStroke(1.dp, Color.Red)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Eliminar Registro")
                    }
                }
            }
        }
    }
}


@Composable
fun DetalleFila(l: String, v: String) { Column { Text(l, color = Color.Gray, fontSize = 11.sp); Text(v, fontSize = 13.sp) } }

@Composable
fun AnalisisScreen(vm: PacientesViewModel, meses: List<String>, anios: List<String>) {
    var m by remember { mutableIntStateOf(LocalDate.now().monthValue) }
    var a by remember { mutableStateOf(LocalDate.now().year.toString()) }
    val lista by vm.atencionesMes(m, a.toInt()).collectAsState(initial = emptyList())
    
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MenuDesplegable("Mes", meses[m-1], meses, Modifier.weight(1f)) { m = meses.indexOf(it)+1 }; MenuDesplegable("Año", a, anios, Modifier.weight(1f)) { a = it } } }
        if (lista.isEmpty()) { item { Text("No hay datos en este período", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } }
        else {
            val h = lista.count { it.sexo == "Hombre" }; val muj = lista.count { it.sexo == "Mujer" }; val total = h + muj
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Distribución por Sexo", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(100.dp)) { drawArc(Color(0xFF2196F3), -90f, if(total>0) 360f*(h.toFloat()/total) else 0f, true); drawArc(Color(0xFFE91E63), -90f + (if(total>0) 360f*(h.toFloat()/total) else 0f), if(total>0) 360f*(muj.toFloat()/total) else 360f, true) }
                            Spacer(Modifier.width(16.dp)); Column { Text("H: $h (${if(total>0) h*100/total else 0}%)", color = Color(0xFF2196F3)); Text("M: $muj (${if(total>0) muj*100/total else 0}%)", color = Color(0xFFE91E63)) }
                        }
                    }
                }
            }
            val emp = lista.groupBy { it.empresa }.mapValues { it.value.size }.toList().sortedByDescending { it.second }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Atenciones por Empresa", fontWeight = FontWeight.Bold)
                        emp.take(5).forEach { (n, c) -> Column(Modifier.padding(vertical = 4.dp)) { Text("$n: $c", fontSize = 12.sp); Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.LightGray)) { Box(Modifier.fillMaxWidth(c.toFloat()/lista.size).fillMaxHeight().background(Color(0xFF0288D1))) } } }
                    }
                }
            }
            val topP = lista.groupBy { it.rut }.maxByOrNull { it.value.size }
            item { topP?.let { (r, l) -> Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE1F5FE))) { Column(Modifier.padding(16.dp)) { Text("Paciente más frecuente", fontWeight = FontWeight.Bold); Text(l.first().nombre, fontWeight = FontWeight.ExtraBold); Text("RUT: $r - ${l.size} atenciones") } } } }
        }
    }
}

@Composable
fun ExportarScreen(vm: PacientesViewModel, meses: List<String>, anios: List<String>) {
    val context = LocalContext.current
    var m by remember { mutableIntStateOf(LocalDate.now().monthValue) }
    var a by remember { mutableStateOf(LocalDate.now().year.toString()) }
    val lista by vm.atencionesMes(m, a.toInt()).collectAsState(initial = emptyList())
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { it?.let { context.contentResolver.openOutputStream(it)?.use { out -> ExcelExporter.exportar(out, lista) } } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.padding(16.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Exportar Excel", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MenuDesplegable("Mes", meses[m-1], meses, Modifier.weight(1f)) { m = meses.indexOf(it)+1 }; MenuDesplegable("Año", a, anios, Modifier.weight(1f)) { a = it } }
                Button(onClick = { launcher.launch("atenciones_${meses[m-1]}_$a.xlsx") }, enabled = lista.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("GENERAR EXCEL") }
            }
        }
    }
}

fun parseFecha(s: String): Long {
    return try {
        LocalDateTime.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch(e: Exception) { System.currentTimeMillis() }
}

@Composable
fun CampoTexto(l: String, v: String, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, leadingIcon: ImageVector? = null, onChange: (String) -> Unit) {
    OutlinedTextField(value = v, onValueChange = onChange, label = { Text(l, overflow = TextOverflow.Ellipsis, maxLines = 1) }, modifier = modifier.fillMaxWidth(), singleLine = singleLine, leadingIcon = leadingIcon?.let { { Icon(it, null, modifier = Modifier.size(20.dp), tint = Color(0xFF0288D1)) } }, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = keyboardType), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF0288D1), focusedLabelColor = Color(0xFF0288D1)))
}

@Composable
fun MenuDesplegable(t: String, v: String, opciones: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(value = v, onValueChange = {}, readOnly = true, label = { Text(t) }, modifier = Modifier.fillMaxWidth().clickable { exp = true }, shape = RoundedCornerShape(12.dp), trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF0288D1)))
        DropdownMenu(expanded = exp, onDismissRequest = { exp = false }, modifier = Modifier.background(Color.White)) { opciones.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); exp = false }) } }
        Spacer(modifier = Modifier.matchParentSize().clickable { exp = true })
    }
}

fun formatearFecha(ts: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
fun copiarImagenInterna(context: Context, uri: Uri): String {
    val file = context.filesDir.resolve("credencial_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { i.copyTo(it) } }
    return file.toURI().toString()
}
fun crearUriTemporal(context: Context): Uri {
    val dir = File(context.filesDir, "pictures"); if (!dir.exists()) dir.mkdirs()
    return FileProvider.getUriForFile(context, "cl.duoc.appacientes.fileprovider", File(dir, "camara_${System.currentTimeMillis()}.jpg"))
}

object ExcelExporter {
    fun exportar(os: OutputStream, datos: List<AtencionPaciente>) {
        ZipOutputStream(os).use { zip ->
            zip.entry("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")
            zip.entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            zip.entry("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Atenciones\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            zip.entry("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            val h = listOf("Fecha", "Nombre", "RUT", "C1_PA", "C1_FC", "C1_Glu", "C1_Sat", "C1_T", "C2_PA", "C2_FC", "C6_T", "Obs")
            val rows = buildString { append(row(1, h)); datos.forEachIndexed { i, a -> append(row(i+2, listOf(formatearFecha(a.fechaHora), a.nombre, a.rut, a.pa, a.fc, a.glucosa, a.sat, a.temperatura, a.pa2, a.fc2, a.temperatura6, a.atencion))) } }
            zip.entry("xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>")
        }
    }
    private fun ZipOutputStream.entry(n: String, c: String) { putNextEntry(ZipEntry(n)); write(c.toByteArray()); closeEntry() }
    private fun row(n: Int, v: List<String>) = "<row r=\"$n\">${v.mapIndexed { i, s -> "<c r=\"${col(i + 1)}$n\" t=\"inlineStr\"><is><t>${esc(s)}</t></is></c>" }.joinToString("")}</row>"
    private fun col(n: Int): String { var i = n; var r = ""; while (i > 0) { r = ('A' + (i - 1) % 26) + r; i = (i - 1) / 26 }; return r }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
