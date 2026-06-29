# Universal National Audio Limiter (UNAL) - Android

Este proyecto es un limitador de audio dinámico y universal para Android. Permite interceptar la sesión de audio global (Sesión 0) a nivel de sistema utilizando privilegios elevados de Shell a través de la API de **Shizuku** (`android.permission.DUMP`), aplicando un filtro a través de la clase nativa `DynamicsProcessing`.

El sistema cuenta con tres innovaciones core implementadas:
1. **Compensación Dinámica de Ganancia (Adaptive Make-up Gain)**.
2. **Conmutación Predictiva de Perfiles por Contexto** (vía `AccessibilityService`).
3. **Algoritmo Preventivo de Dosimetría** (para evitar la fatiga auditiva).

---

# 🚀 Guía de Configuración Inicial (Primera vez)

> **Importante:** Para que la aplicación funcione correctamente, sigue los pasos en el orden indicado. Android bloquea por defecto algunos permisos cuando una aplicación se instala fuera de Google Play.

---

## Paso 0: Permitir Ajustes Restringidos (Solo Android 13+)

> **Nota:** Si la opción **"Permitir ajustes restringidos"** no aparece, significa que ya fue habilitada anteriormente y puedes continuar con el siguiente paso.

Cuando la aplicación se instala mediante Android Studio, ADB o cualquier método diferente a Google Play, Android restringe el acceso a funciones sensibles como los Servicios de Accesibilidad.

Para habilitarlo:

1. Ve a la pantalla de inicio de tu dispositivo.
2. Mantén presionado durante unos segundos el ícono de **UNAL Limiter**.
3. Pulsa el botón **Información** (ícono de la **i** dentro de un círculo).
4. En la pantalla de información de la aplicación, abre el menú de los **tres puntos (⋮)** ubicado en la esquina superior derecha.
5. Selecciona **Permitir ajustes restringidos** (*Allow restricted settings*).
6. Confirma con tu huella, PIN o patrón si Android lo solicita.

---

## Paso 1: Activar el Servicio de Accesibilidad

Este paso es obligatorio para que la aplicación pueda detectar automáticamente qué aplicación multimedia está abierta (Spotify, YouTube, etc.) y cambiar el perfil de procesamiento correspondiente.

1. Abre **Configuración** del dispositivo.
2. Entra en **Accesibilidad**.
3. Abre **Apps** o **Servicios instalados** (el nombre puede variar según el fabricante).
4. Busca **UNAL Context Service**.
5. Activa el servicio.
6. Si Android muestra una advertencia de seguridad:
   - Espera que termine la cuenta regresiva.
   - Marca la casilla de confirmación.
   - Pulsa **Aceptar**.

---

## Paso 2: Configurar Shizuku

La aplicación **Shizuku** debe estar instalada y ejecutándose.

- Descarga Shizuku desde la Play Store o desde GitHub.
- Inicia el servicio mediante **Wireless Debugging (Depuración inalámbrica)**.
- También puedes iniciarlo mediante ADB:

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.manager/files/start.sh
```

---

## Paso 3: Compilar e Instalar desde Android Studio

1. Abre el proyecto con **Android Studio**.
2. Espera a que Gradle descargue todas las dependencias.
3. Conecta el dispositivo con **Depuración USB** habilitada.
4. Presiona **Run ▶**.

---

## Paso 4: Primer Inicio

1. Abre **UNAL Limiter**.
2. Comprueba las tres tarjetas de estado:

- **Servicio Shizuku** → debe aparecer como **Disponible**.
- **Permiso API Shizuku** → si aparece **Falta Permiso**, pulsa **Pedir Permiso** y acepta el cuadro de diálogo de Shizuku.
- **Estado Servicio UAL** → debe pasar a **Persistente (Activo)**.

3. Pulsa **Iniciar Servicio**.

4. Aparecerá una notificación persistente indicando el perfil activo.

5. Si hay audio reproduciéndose, los vúmetros y el analizador FFT comenzarán a mostrar actividad.

6. Abre aplicaciones como Spotify o YouTube para comprobar que el perfil cambia automáticamente.

7. Puedes volver a la aplicación para modificar manualmente:

- Threshold
- Attack
- Release
- Post-Gain

cuando estés utilizando el Perfil General.

---

# 🎛️ Controles disponibles

| Control | Rango | Descripción |
|---|---|---|
| **Threshold** | -40 a 0 dBFS | Techo máximo de la señal. Todo lo que supere este valor es limitado. |
| **Attack** | 1 a 100 ms | Velocidad de reacción del limitador al detectar un pico. |
| **Release** | 10 a 500 ms | Velocidad de recuperación tras el pico. |
| **Post-Gain** | 0 a 15 dB | Ganancia aplicada después del limitador. |
| **Ganancia Adaptativa** | Switch | Amplifica automáticamente señales débiles para compensar el silencio. |
| **Dosímetro de Fatiga** | Switch | Reduce dinámicamente el Threshold cuando se supera la exposición sonora segura. |

---

# 🪵 Depuración (Logcat)

Puedes observar el funcionamiento interno filtrando Logcat por:

- `UNALUserService` — Motor DSP y Visualizer.
- `UNALForegroundService` — Servicio en segundo plano y cambios de perfil.
- `ShizukuManager` — Comunicación Binder con Shizuku.