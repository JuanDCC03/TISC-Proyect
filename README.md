# Universal National Audio Limiter (UNAL) - Android

Este proyecto es un limitador de audio dinámico y universal para Android. Permite interceptar la sesión de audio global (Sesión 0) a nivel de sistema utilizando privilegios elevados de Shell a través de la API de **Shizuku** (`android.permission.DUMP`), aplicando un filtro a través de la clase nativa `DynamicsProcessing`.

El sistema cuenta con tres innovaciones core planificadas:
1. **Compensación Dinámica de Ganancia (Adaptive Make-up Gain)**.
2. **Conmutación Predictiva de Perfiles por Contexto** (vía `AccessibilityService`).
3. **Algoritmo Preventivo de Dosimetría** (para evitar la fatiga auditiva).

Actualmente está implementado el **Bloque 2: Shizuku Manager y la Infraestructura Base** junto con una utilidad de pruebas en la interfaz principal.

---

## 🚀 Guía de Pruebas Rápidas (Bloque 2)

Sigue estos pasos para verificar el estado de Shizuku, la solicitud de permisos, la vinculación del Binder y el análisis RMS del audio global en tiempo real:

### 1. Importar el Proyecto en Android Studio
1. Abre **Android Studio**.
2. Selecciona **Open** y selecciona esta carpeta del proyecto.
3. Android Studio detectará los archivos Gradle (`settings.gradle.kts` y `build.gradle.kts`), generará el Gradle Wrapper y descargará las dependencias de Shizuku y Jetpack Compose automáticamente.

### 2. Configurar Shizuku en tu Dispositivo
La aplicación oficial de **Shizuku** debe estar instalada y ejecutándose en el dispositivo o emulador de prueba.
* Si utilizas un emulador o un dispositivo físico conectado por USB, puedes iniciar el servicio de Shizuku ejecutando el siguiente comando de ADB en tu terminal:
  ```bash
  adb shell sh /sdcard/Android/data/moe.shizuku.manager/files/start.sh
  ```
* También puedes iniciar Shizuku directamente desde el dispositivo usando la opción de *Wireless Debugging* dentro de la propia app de Shizuku.

### 3. Compilar e Instalar
1. Conecta el dispositivo y presiona el botón **Run** en Android Studio.
2. Abre la app **UNAL Limiter** instalada.

### 4. Ejecución del Test en la App
1. **Verificar / Solicitar Permiso**: Haz clic en el botón **"Verificar / Solicitar Permiso Shizuku"**. Se abrirá una ventana emergente de Shizuku solicitando acceso para la app. Acepta el permiso.
2. El indicador de **Permiso de API** en la aplicación cambiará a **Concedido** (verde).
3. **Arrancar el Motor**: Haz clic en **"Conectar y Arrancar Motor"**. 
   * Esto vincula el servicio de usuario de Shizuku (`UNALUserService`), el cual corre en segundo plano como `shell`.
   * El servicio inicializa la clase nativa `DynamicsProcessing` y el lector `Visualizer` en la sesión `0` global.
   * El estado del Binder en la interfaz cambiará a **"Motor Activo en Sesión 0"**.
4. **Monitorear Audio**: Sal de la aplicación y reproduce cualquier audio en otra app (como YouTube o Spotify). Al regresar a la utilidad de pruebas de UNAL Limiter, verás fluctuar en tiempo real los valores de **RMS de Salida (dBFS)** y la estimación del **Dosímetro de Fatiga**, confirmando el éxito del procesamiento del audio y la comunicación IPC.
5. Puedes observar los logs del proceso Shizuku en la consola Logcat de Android Studio filtrando por `UNALUserService` o `ShizukuManager`.