package cl.duoc.appacientes

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Event
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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

    val pa: String,
    val fc: String,
    val glucosa: String,
    val sat: String,
    val temperatura: String,

    val enfermedadCronica: String,
    val otraEnfermedad: String,

    val motivoAsistenciaEnfermeria: String,
    val atencion: String,

    val fotoCredencialUri: String
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

    @Query("""
        SELECT * FROM atenciones 
        WHERE LOWER(nombre) LIKE '%' || LOWER(:texto) || '%'
        OR LOWER(rut) LIKE '%' || LOWER(:texto) || '%'
        OR LOWER(piso) LIKE '%' || LOWER(:texto) || '%'
        ORDER BY fechaHora DESC
    """)
    fun buscar(texto: String): Flow<List<AtencionPaciente>>

    @Query("""
        SELECT * FROM atenciones 
        WHERE fechaHora BETWEEN :inicio AND :fin
        ORDER BY fechaHora DESC
    """)
    fun entreFechas(inicio: Long, fin: Long): Flow<List<AtencionPaciente>>
}

@Database(entities = [AtencionPaciente::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun atencionDao(): AtencionDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pacientes_db"
                ).build()
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
    fun buscar(texto: String) = if (texto.isBlank()) dao.obtenerTodas() else dao.buscar(texto)

    fun atencionesHoy(): Flow<List<AtencionPaciente>> {
        val hoy = LocalDate.now()
        val inicio = hoy.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fin = hoy.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        return dao.entreFechas(inicio, fin)
    }

    fun atencionesMes(mes: Int, anio: Int): Flow<List<AtencionPaciente>> {
        val inicioMes = LocalDate.of(anio, mes, 1)
        val inicio = inicioMes.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fin = inicioMes.plusMonths(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        return dao.entreFechas(inicio, fin)
    }

    suspend fun guardar(atencion: AtencionPaciente) {
        if (atencion.id == 0L) dao.insertar(atencion)
        else dao.actualizar(atencion.copy(actualizado = System.currentTimeMillis()))
    }

    suspend fun eliminar(atencion: AtencionPaciente) {
        dao.eliminar(atencion)
    }
}

class PacientesViewModel(private val repo: AtencionRepository) : ViewModel() {
    val todas = repo.todas
    val hoy = repo.atencionesHoy()
    
    fun atencionesMes(mes: Int, anio: Int) = repo.atencionesMes(mes, anio)

    fun buscar(texto: String) = repo.buscar(texto)
    suspend fun guardar(atencion: AtencionPaciente) = repo.guardar(atencion)
    suspend fun eliminar(atencion: AtencionPaciente) = repo.eliminar(atencion)
}

class PacientesViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.get(context)
        return PacientesViewModel(AtencionRepository(db.atencionDao())) as T
    }
}

// -----------------------------------------------------
// MAIN ACTIVITY
// -----------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppPacientes()
        }
    }
}

// -----------------------------------------------------
// UI PRINCIPAL
// -----------------------------------------------------

@Composable
fun AppPacientes() {
    val context = LocalContext.current
    val vm: PacientesViewModel = viewModel(factory = PacientesViewModelFactory(context))
    var mostrarCarga by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(2500) // Simula carga
        mostrarCarga = false
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0288D1),
            secondary = Color(0xFF03A9F4),
            tertiary = Color(0xFF00BCD4),
            background = Color(0xFFF0F7FA),
            surface = Color.White
        )
    ) {
        Crossfade(targetState = mostrarCarga, label = "carga") { carga ->
            if (carga) {
                PantallaCarga()
            } else {
                ContenidoPrincipal(vm)
            }
        }
    }
}

@Composable
fun PantallaCarga() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0288D1), Color(0xFF01579B)))),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "anim")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "scale"
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MedicalServices,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(60.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "App Pacientes",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        }

        Text(
            "Desarrollado por AmuleyDev",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
    }
}

@Composable
fun ContenidoPrincipal(vm: PacientesViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var editando by remember { mutableStateOf<AtencionPaciente?>(null) }

    val meses = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
    val anios = (2024..2030).map { it.toString() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { HeaderApp() },
        bottomBar = { FooterApp() }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF0F7FA))
        ) {
            val tabs = listOf(
                "Registro" to Icons.Default.AddCircle,
                "Buscar" to Icons.Default.Search,
                "Hoy" to Icons.Default.Today,
                "Mes" to Icons.Default.CalendarMonth,
                "Exportar" to Icons.Default.FileDownload
            )

            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = Color.White,
                contentColor = Color(0xFF0277BD),
                edgePadding = 16.dp,
                divider = {},
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[tab]),
                        color = Color(0xFF0288D1),
                        height = 3.dp
                    )
                }
            ) {
                tabs.forEachIndexed { index, (title, icon) ->
                    Tab(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            if (index != 0) editando = null
                        },
                        text = {
                            Text(
                                title,
                                fontSize = 13.sp,
                                fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> RegistroScreen(vm, editando) { editando = null }
                    1 -> BuscarScreen(vm) { editando = it; tab = 0 }
                    2 -> ListaAtencionesScreen("Pacientes de hoy", vm.hoy, vm) { editando = it; tab = 0 }
                    3 -> MesFiltroScreen(vm, meses, anios) { editando = it; tab = 0 }
                    4 -> ExportarScreen(vm, meses, anios)
                }
            }
        }
    }
}

@Composable
fun HeaderApp() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0288D1),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Registro de Atenciones",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Gestión eficiente de pacientes",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun FooterApp() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Text(
            "Desarrollado por AmuleyDev",
            modifier = Modifier
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// -----------------------------------------------------
// PANTALLA REGISTRO
// -----------------------------------------------------

@Composable
fun RegistroScreen(vm: PacientesViewModel, editando: AtencionPaciente?, onGuardado: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nombre by remember(editando) { mutableStateOf(editando?.nombre ?: "") }
    var rut by remember(editando) { mutableStateOf(editando?.rut ?: "") }
    var piso by remember(editando) { mutableStateOf(editando?.piso ?: "") }
    var empresa by remember(editando) { mutableStateOf(editando?.empresa ?: "Caja Los Andes") }
    var sexo by remember(editando) { mutableStateOf(editando?.sexo ?: "No informado") }
    var motivoConsulta by remember(editando) { mutableStateOf(editando?.motivoConsulta ?: "Chequeo hábito saludable") }
    var categorizacion by remember(editando) { mutableStateOf(editando?.categorizacion ?: "C5") }
    var servicio by remember(editando) { mutableStateOf(editando?.servicio ?: "Enfermería") }
    var pa by remember(editando) { mutableStateOf(editando?.pa ?: "") }
    var fc by remember(editando) { mutableStateOf(editando?.fc ?: "") }
    var glucosa by remember(editando) { mutableStateOf(editando?.glucosa ?: "") }
    var sat by remember(editando) { mutableStateOf(editando?.sat ?: "") }
    var temperatura by remember(editando) { mutableStateOf(editando?.temperatura ?: "") }
    var enfermedadCronica by remember(editando) { mutableStateOf(editando?.enfermedadCronica ?: "Sin registro") }
    var otraEnfermedad by remember(editando) { mutableStateOf(editando?.otraEnfermedad ?: "") }
    var motivoAsistencia by remember(editando) { mutableStateOf(editando?.motivoAsistenciaEnfermeria ?: "") }
    var atencion by remember(editando) { mutableStateOf(editando?.atencion ?: "") }
    var fotoUri by remember(editando) { mutableStateOf(editando?.fotoCredencialUri ?: "") }
    var mensaje by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { fotoUri = copiarImagenInterna(context, it) }
    }

    var tempUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempUri?.let { fotoUri = it.toString() }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            try {
                val uri = crearUriTemporal(context)
                tempUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                mensaje = "❌ Error al preparar la cámara: ${e.message}"
            }
        } else {
            mensaje = "❌ Permiso de cámara denegado"
        }
    }

    var mostrarOpcionesFoto by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        if (editando == null) "Nueva Atención" else "Modificar Registro",
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0277BD),
                        fontSize = 20.sp
                    )

                    SeccionTitulo("Datos del Paciente", Icons.Default.Person)
                    CampoTexto("Nombre Completo", nombre) { nombre = it }
                    CampoTexto("RUT", rut) { rut = it }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CampoTexto("Piso", piso, Modifier.weight(1f)) { piso = it }
                        MenuDesplegable("Sexo", sexo, listOf("Hombre", "Mujer", "No informado"), Modifier.weight(1.5f)) { sexo = it }
                    }

                    MenuDesplegable("Empresa / Agencia", empresa, listOf("Caja Los Andes", "Turismo", "Externo", "Tapp", "Linkes", "Aseo Express", "A. práctica", "Sala monitoreo")) { empresa = it }

                    Divider(color = Color.LightGray.copy(alpha = 0.5f))
                    SeccionTitulo("Fecha y Hora de Registro", Icons.Default.Event)
                    
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

                    Divider(color = Color.LightGray.copy(alpha = 0.5f))
                    SeccionTitulo("Evaluación Clínica", Icons.Default.Thermostat)

                    MenuDesplegable("Motivo de consulta", motivoConsulta, listOf("Chequeo hábito saludable", "Chequeo preventivo", "Atención de enfermería", "Urgencia", "Urgencia laboral")) { motivoConsulta = it }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MenuDesplegable("Categoría", categorizacion, listOf("C1", "C2", "C3", "C4", "C5"), Modifier.weight(1f)) { categorizacion = it }
                        MenuDesplegable("Servicio", servicio, listOf("Enfermería", "Control en piso"), Modifier.weight(1.5f)) { servicio = it }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CampoTexto("P/A", pa, Modifier.weight(1f)) { pa = it }
                        CampoTexto("FC", fc, Modifier.weight(1f), KeyboardType.Number) { fc = it }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CampoTexto("Glucosa", glucosa, Modifier.weight(1f), KeyboardType.Number) { glucosa = it }
                        CampoTexto("Sat %", sat, Modifier.weight(1f), KeyboardType.Number) { sat = it }
                    }

                    CampoTexto("Temperatura °C", temperatura, keyboardType = KeyboardType.Decimal) { temperatura = it }

                    Divider(color = Color.LightGray.copy(alpha = 0.5f))
                    SeccionTitulo("Antecedentes y Notas", Icons.Default.Description)

                    MenuDesplegable("Enfermedad crónica", enfermedadCronica, listOf("Sin registro", "Hipertensión", "Diabetes", "Asma", "Disautonomía", "Hipotiroidismo", "Otra")) { enfermedadCronica = it }
                    if (enfermedadCronica == "Otra") {
                        CampoTexto("Especifique enfermedad", otraEnfermedad) { otraEnfermedad = it }
                    }

                    CampoTexto("Motivo asistencia", motivoAsistencia, singleLine = false) { motivoAsistencia = it }
                    CampoTexto("Observaciones", atencion, singleLine = false) { atencion = it }

                    Button(
                        onClick = { mostrarOpcionesFoto = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1F5FE), contentColor = Color(0xFF0288D1)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Foto de Credencial")
                    }

                    if (mostrarOpcionesFoto) {
                        AlertDialog(
                            onDismissRequest = { mostrarOpcionesFoto = false },
                            title = { Text("Seleccionar origen") },
                            text = { Text("¿Deseas tomar una foto o elegir una de la galería?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    mostrarOpcionesFoto = false
                                    permissionLauncher.launch(android.Manifest.permission.CAMERA)
                                }) { Text("Cámara") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    mostrarOpcionesFoto = false
                                    photoPicker.launch("image/*")
                                }) { Text("Galería") }
                            }
                        )
                    }

                    if (fotoUri.isNotBlank()) {
                        Image(
                            painter = rememberAsyncImagePainter(fotoUri),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFFF5F5F5)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .shadow(8.dp, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                            onClick = {
                                if (nombre.isBlank() || rut.isBlank()) {
                                    mensaje = "⚠️ Nombre y RUT son obligatorios"
                                    return@Button
                                }
                                
                                // Parsear fecha y hora manual
                                val finalTimestamp = try {
                                    val fechaPartes = fechaStr.split("/")
                                    val horaPartes = horaStr.split(":")
                                    LocalDateTime.of(
                                        fechaPartes[2].toInt(), fechaPartes[1].toInt(), fechaPartes[0].toInt(),
                                        horaPartes[0].toInt(), horaPartes[1].toInt()
                                    ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                } catch (e: Exception) {
                                    System.currentTimeMillis()
                                }

                                val nueva = AtencionPaciente(
                                    id = editando?.id ?: 0,
                                    fechaHora = finalTimestamp,
                                    nombre = nombre.trim(), rut = rut.trim(), piso = piso.trim(),
                                empresa = empresa, sexo = sexo, motivoConsulta = motivoConsulta,
                                categorizacion = categorizacion, servicio = servicio,
                                pa = pa.trim(), fc = fc.trim(), glucosa = glucosa.trim(),
                                sat = sat.trim(), temperatura = temperatura.trim(),
                                enfermedadCronica = enfermedadCronica, otraEnfermedad = otraEnfermedad.trim(),
                                motivoAsistenciaEnfermeria = motivoAsistencia.trim(),
                                atencion = atencion.trim(), fotoCredencialUri = fotoUri
                            )
                            scope.launch {
                                vm.guardar(nueva)
                                mensaje = if (editando == null) "✅ Guardado con éxito" else "✅ Cambios guardados"
                                if (editando == null) {
                                    nombre = ""; rut = ""; piso = ""; pa = ""; fc = ""; glucosa = ""; sat = ""
                                    temperatura = ""; otraEnfermedad = ""; motivoAsistencia = ""; atencion = ""; fotoUri = ""
                                }
                                delay(2000)
                                mensaje = ""
                                onGuardado()
                            }
                        }
                    ) {
                        Text(if (editando == null) "REGISTRAR ATENCIÓN" else "ACTUALIZAR DATOS", fontWeight = FontWeight.Bold)
                    }

                    AnimatedVisibility(visible = mensaje.isNotBlank()) {
                        Text(
                            mensaje,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color(0xFF0277BD),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (editando != null) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            onClick = { onGuardado() }
                        ) {
                            Text("CANCELAR / VOLVER", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun SeccionTitulo(titulo: String, icono: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(icono, null, tint = Color(0xFF0288D1), modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(titulo, color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// -----------------------------------------------------
// BUSCAR / LISTAS
// -----------------------------------------------------

@Composable
fun BuscarScreen(vm: PacientesViewModel, onEditar: (AtencionPaciente) -> Unit) {
    var texto by remember { mutableStateOf("") }
    val resultados by vm.buscar(texto).collectAsState(initial = emptyList())

    Column(modifier = Modifier.padding(16.dp)) {
        CampoTexto("Buscar por nombre, RUT o piso", texto, leadingIcon = Icons.Default.Search) { texto = it }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Resultados: ${resultados.size}", fontWeight = FontWeight.Bold, color = Color(0xFF01579B))
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(resultados) { item -> TarjetaAtencion(item, vm, onEditar) }
        }
    }
}

@Composable
fun MesFiltroScreen(vm: PacientesViewModel, meses: List<String>, anios: List<String>, onEditar: (AtencionPaciente) -> Unit) {
    var mesSel by remember { mutableIntStateOf(LocalDate.now().monthValue) }
    var anioSel by remember { mutableStateOf(LocalDate.now().year.toString()) }
    
    val flowMes = remember(mesSel, anioSel) { vm.atencionesMes(mesSel, anioSel.toInt()) }
    val lista by flowMes.collectAsState(initial = emptyList())

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Pacientes por período", fontWeight = FontWeight.ExtraBold, color = Color(0xFF01579B), fontSize = 22.sp)
        
        Row(modifier = Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuDesplegable("Mes", meses[mesSel - 1], meses, Modifier.weight(1f)) { 
                mesSel = meses.indexOf(it) + 1 
            }
            MenuDesplegable("Año", anioSel, anios, Modifier.weight(1f)) { 
                anioSel = it 
            }
        }

        Text("Total de registros: ${lista.size}", color = Color(0xFF0277BD), fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(lista) { item -> TarjetaAtencion(item, vm, onEditar) }
        }
    }
}

@Composable
fun ListaAtencionesScreen(titulo: String, flow: Flow<List<AtencionPaciente>>, vm: PacientesViewModel, onEditar: (AtencionPaciente) -> Unit) {
    val lista by flow.collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(16.dp)) {
        Text(titulo, fontWeight = FontWeight.ExtraBold, color = Color(0xFF01579B), fontSize = 22.sp)
        Text("Total de registros: ${lista.size}", color = Color(0xFF0277BD), fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(lista) { item -> TarjetaAtencion(item, vm, onEditar) }
        }
    }
}

@Composable
fun TarjetaAtencion(item: AtencionPaciente, vm: PacientesViewModel, onEditar: (AtencionPaciente) -> Unit) {
    val scope = rememberCoroutineScope()
    var expandida by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expandida = !expandida },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).background(Color(0xFFE1F5FE), CircleShape), contentAlignment = Alignment.Center) {
                    Text(item.nombre.take(1).uppercase(), color = Color(0xFF0288D1), fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.nombre, fontWeight = FontWeight.Bold, color = Color(0xFF01579B), fontSize = 16.sp)
                    Text("RUT: ${item.rut}", fontSize = 13.sp, color = Color.Gray)
                }
                Icon(if (expandida) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Color.Gray)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                BadgeInfo(item.piso, Icons.Default.Layers)
                Spacer(modifier = Modifier.width(8.dp))
                BadgeInfo(formatearFecha(item.fechaHora), Icons.Default.AccessTime)
            }

            AnimatedVisibility(visible = expandida) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.LightGray.copy(alpha = 0.4f))
                    DetalleFila("Empresa", item.empresa)
                    DetalleFila("Motivo Consulta", item.motivoConsulta)
                    DetalleFila("Servicio", item.servicio)
                    DetalleFila("Categoría", item.categorizacion)
                    DetalleFila("Sexo", item.sexo)
                    
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        DetalleFila("P/A", item.pa, Modifier.weight(1f))
                        DetalleFila("FC", item.fc, Modifier.weight(1f))
                    }

                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        DetalleFila("Glucosa", item.glucosa, Modifier.weight(1f))
                        DetalleFila("Sat %", item.sat, Modifier.weight(1f))
                        DetalleFila("Temp °C", item.temperatura, Modifier.weight(1f))
                    }
                    
                    DetalleFila("Antecedentes", "${item.enfermedadCronica}${if(item.otraEnfermedad.isNotBlank()) " (${item.otraEnfermedad})" else ""}")
                    DetalleFila("Motivo Asistencia", item.motivoAsistenciaEnfermeria)

                    Text("Atención / Observaciones:", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = Color(0xFF0277BD))
                    Text(item.atencion, fontSize = 13.sp)

                    if (item.fotoCredencialUri.isNotBlank()) {
                        Image(
                            painter = rememberAsyncImagePainter(item.fotoCredencialUri),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onEditar(item) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Editar") }
                        
                        OutlinedButton(
                            onClick = { scope.launch { vm.eliminar(item) } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeInfo(texto: String, icono: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Icon(icono, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
        Spacer(modifier = Modifier.width(4.dp))
        Text(texto, fontSize = 11.sp, color = Color.DarkGray)
    }
}

@Composable
fun DetalleFila(label: String, valor: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 2.dp)) {
        Text(label, color = Color.Gray, fontSize = 11.sp)
        Text(valor, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

// -----------------------------------------------------
// EXPORTAR
// -----------------------------------------------------

@Composable
fun ExportarScreen(vm: PacientesViewModel, meses: List<String>, anios: List<String>) {
    val context = LocalContext.current
    var mesSel by remember { mutableIntStateOf(LocalDate.now().monthValue) }
    var anioSel by remember { mutableStateOf(LocalDate.now().year.toString()) }
    
    val flowMes = remember(mesSel, anioSel) { vm.atencionesMes(mesSel, anioSel.toInt()) }
    val lista by flowMes.collectAsState(initial = emptyList())
    
    var mensaje by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out -> ExcelExporter.exportar(out, lista) }
            mensaje = "✅ Excel generado correctamente"
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.TableView, null, modifier = Modifier.size(64.dp), tint = Color(0xFF0288D1))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Exportar por Período", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                
                Row(modifier = Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuDesplegable("Mes", meses[mesSel - 1], meses, Modifier.weight(1f)) { 
                        mesSel = meses.indexOf(it) + 1 
                    }
                    MenuDesplegable("Año", anioSel, anios, Modifier.weight(1f)) { 
                        anioSel = it 
                    }
                }

                Text("Registros a exportar: ${lista.size}", fontWeight = FontWeight.Bold, color = Color(0xFF0288D1))
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = lista.isNotEmpty(),
                    onClick = { launcher.launch("atenciones_${meses[mesSel-1]}_$anioSel.xlsx") }
                ) { Text("GENERAR EXCEL", fontWeight = FontWeight.Bold) }
                if (mensaje.isNotBlank()) {
                    Text(mensaje, modifier = Modifier.padding(top = 16.dp), color = Color(0xFF0288D1), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// -----------------------------------------------------
// COMPONENTES
// -----------------------------------------------------

@Composable
fun CampoTexto(label: String, value: String, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, leadingIcon: ImageVector? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        leadingIcon = leadingIcon?.let { { Icon(it, null, modifier = Modifier.size(20.dp), tint = Color(0xFF0288D1)) } },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF0288D1), focusedLabelColor = Color(0xFF0288D1))
    )
}

@Composable
fun MenuDesplegable(titulo: String, valor: String, opciones: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = valor, onValueChange = {}, readOnly = true, label = { Text(titulo) },
            modifier = Modifier.fillMaxWidth().clickable { abierto = true },
            shape = RoundedCornerShape(12.dp), trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF0288D1))
        )
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }, modifier = Modifier.background(Color.White).fillMaxWidth(0.8f)) {
            opciones.forEach { opcion ->
                DropdownMenuItem(text = { Text(opcion) }, onClick = { onSelect(opcion); abierto = false })
            }
        }
        Spacer(modifier = Modifier.matchParentSize().clickable { abierto = true })
    }
}

// -----------------------------------------------------
// UTILIDADES / EXCEL
// -----------------------------------------------------

fun formatearFecha(ts: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

fun copiarImagenInterna(context: Context, uri: Uri): String {
    val file = context.filesDir.resolve("credencial_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
    return file.toURI().toString()
}

fun crearUriTemporal(context: Context): Uri {
    val directory = File(context.filesDir, "pictures")
    if (!directory.exists()) directory.mkdirs()
    val file = File(directory, "camara_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "cl.duoc.appacientes.fileprovider", file)
}

object ExcelExporter {
    fun exportar(os: OutputStream, datos: List<AtencionPaciente>) {
        ZipOutputStream(os).use { zip ->
            zip.entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            zip.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            zip.entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Atenciones" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            zip.entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            
            val headers = listOf("Fecha", "Hora", "Nombre", "RUT", "Empresa", "Sexo", "Piso", "Motivo", "Cat", "Servicio", "PA", "FC", "Glu", "Sat", "Temp", "Patología", "Observación")
            val rows = buildString {
                append(row(1, headers))
                datos.forEachIndexed { i, a ->
                    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(a.fechaHora), ZoneId.systemDefault())
                    append(row(i + 2, listOf(dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), dt.format(DateTimeFormatter.ofPattern("HH:mm")), a.nombre, a.rut, a.empresa, a.sexo, a.piso, a.motivoConsulta, a.categorizacion, a.servicio, a.pa, a.fc, a.glucosa, a.sat, a.temperatura, a.enfermedadCronica, a.atencion)))
                }
            }
            zip.entry("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
        }
    }
    private fun ZipOutputStream.entry(n: String, c: String) { putNextEntry(ZipEntry(n)); write(c.toByteArray()); closeEntry() }
    private fun row(n: Int, v: List<String>) = "<row r=\"$n\">${v.mapIndexed { i, s -> "<c r=\"${col(i + 1)}$n\" t=\"inlineStr\"><is><t>${esc(s)}</t></is></c>" }.joinToString("")}</row>"
    private fun col(n: Int): String { var i = n; var r = ""; while (i > 0) { r = ('A' + (i - 1) % 26) + r; i = (i - 1) / 26 }; return r }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
