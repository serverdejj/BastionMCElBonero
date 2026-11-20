# BastionMCElBonero 1.0.0 (proyecto fuente)

Plugin independiente para **Purpur 1.21.11**, Java 21. Utiliza la entidad vanilla `MANNEQUIN`, que existe desde Minecraft 1.21.9, sin Citizens.

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
La skin `elbonero.png` (64x64) está incluida **como referencia** pero Minecraft no puede utilizar directamente una ruta PNG local en perfiles. Es necesario subirla a un servicio de generación de texturas de Minecraft, obtener el enlace de textura `https://textures.minecraft.net/texture/ID`, y colocarlo en `config.yml` -> `bonero.skin.texture-url`. Después reinicia o `/bonero reload`.

## Límites actuales
- GUI muestra los primeros 28 intercambios (pueden existir más en YAML, pero no hay paginación).
- No hay límite de compra por jugador ni sistema de múltiples comerciantes en esta versión.
- El NPC es `Mannequin` (apariencia de jugador), no una entidad `Player`; por tanto, puede diferir ligeramente en animaciones.
- No se han efectuado pruebas de compilación o de integración sobre Purpur 1.21.11; usa primero un servidor de pruebas.
