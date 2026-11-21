# BastionMCElBonero 1.1.0 (proyecto fuente)

Plugin independiente para **Purpur 1.21.11**, Java 21. Usa **FancyNpcs** como dependencia obligatoria para evitar el bloqueo de mobs en WorldGuard. Instala FancyNpcs compatible con tu versión de Purpur.

## Compilar sin instalar nada
1. Sube el contenido de esta carpeta a un repositorio GitHub (conservando rutas).
2. Abre **Actions > Build BastionMCElBonero > Run workflow**, o haz un push.
3. Descarga `BastionMCElBonero-JAR` desde Artifacts.
4. Copia el archivo `.jar` a `/plugins` y reinicia.

El ZIP contiene el **código fuente, no un JAR compilado ni probado en un servidor real**.

## Comandos
- `/elbonero set location 1` hasta las ubicaciones que quieras (estando en ellas)
- `/elbonero spawn location 1`
- `/elbonero remove location 1`
- `/elbonero locations`
- `/elbonero status`
- `/elbonero despawn`
- `/elbonero edit trade` (haz clic izquierdo con un ítem en el cursor para COPIARLO a una casilla, clic derecho para borrar; guarda con el tinte verde)
- `/bonero reload`

Permiso: `elbonero.admin` (OP de forma predeterminada).

## Cómo funciona
- Se elige una ubicación aleatoria (evita repetir la última, si hay más de una).
- Permanece 5 minutos (configurable) y reaparece 2 horas después de desaparecer (configurable).
- Mensajes y sonidos globales personalizables en `messages.yml` y `config.yml`.
- Nombre y holograma flotante con contador de segundos.
- Los intercambios usan los ítems reales, preservando metadatos (nombres, encantamientos, etc.).
- Los datos se guardan en `locations.yml`, `trades.yml` y `state.yml`.
- El editor no cobra los objetos usados para configurar los intercambios; usa COPIAS. Es recomendable editar con objetos preparados en creativo.
- El stock de un intercambio es `-1` (ilimitado) por defecto. Se puede configurar `trades.<id>.stock` en `trades.yml` manualmente.

## Skin personalizada
La skin `elbonero.png` se copia automáticamente a `plugins/BastionMCElBonero/elbonero.png`. FancyNpcs intentará cargarla desde esa ruta; si tu versión no admite rutas absolutas, configura `bonero.skin.source` con una URL de skin o nombre de jugador.

**Importante:** la versión de FancyNpcs instalada debe ser compatible con 1.21.11 y con esta API. El archivo ZIP es código fuente para compilar en GitHub; aún no está probado en tu servidor.

## Skin anterior
La skin `elbonero.png` (64x64) está incluida **como referencia** pero Minecraft no puede utilizar directamente una ruta PNG local en perfiles. Es necesario subirla a un servicio de generación de texturas de Minecraft, obtener el enlace de textura `https://textures.minecraft.net/texture/ID`, y colocarlo en `config.yml` -> `bonero.skin.texture-url`. Después reinicia o `/bonero reload`.

## Límites actuales
- GUI muestra los primeros 28 intercambios (pueden existir más en YAML, pero no hay paginación).
- No hay límite de compra por jugador ni sistema de múltiples comerciantes en esta versión.
- El NPC es `Mannequin` (apariencia de jugador), no una entidad `Player`; por tanto, puede diferir ligeramente en animaciones.
- No se han efectuado pruebas de compilación o de integración sobre Purpur 1.21.11; usa primero un servidor de pruebas.

## v1.2 — limpieza de hologramas y contador
- Los `TextDisplay` creados por El Bonero llevan una etiqueta PDC exclusiva del plugin.
- Al iniciar, al cargar un chunk, al desaparecer, y en comprobaciones periódicas cuando el evento está inactivo, se retiran **solo** los hologramas huérfanos con esa etiqueta.
- El contador se calcula cada segundo a partir de la hora real de expiración.
- `/elbonero despawn` permite limpiar hologramas etiquetados aunque no haya evento activo.
- El spawn manual no informa éxito si la creación del NPC falló.

**Importante:** si quedan hologramas de una versión antigua sin etiqueta PDC o de otro plugin, no se borran automáticamente para proteger hologramas ajenos. Para limpiar la versión anterior, primero haz una copia de seguridad y prueba la versión 1.2. No elimines la carpeta de datos del plugin al actualizar.
