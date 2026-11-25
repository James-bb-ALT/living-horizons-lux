# Living Horizons: Lux (`livinghorizons_lux`)

Addon client-side para **Minecraft 1.20.1 (Forge)** que expande o **Living Horizons** mantendo 100% de suas features nativas (névoa por bioma, feixes de luz, névoa de solo, vento/plantas balançando, vida ambiente, chuva inclinada, céu noturno e color grading) enquanto adiciona:

1. **Sombras em Cascata GPU (CSM) + Sombras de Entidades**:
   - Duas cascatas ortográficas (próxima e distante) renderizadas diretamente dos buffers de chunk do **Embeddium 0.3.31** com *texel snapping*, *receiver-plane depth gradient bias* e PCF 2x2 / 4-tap, substituindo o volume voxel pesado do Living Horizons sem sacrificar nenhum dos outros efeitos dele.
2. **Iluminação Colorida RGB por Bloco**:
   - Motor assíncrono em 12-bit (4-4-4 RGB) com filtros de vidro colorido, detecção automática para blocos emissores de mods e equilíbrio automático com o `warmLight` do Living Horizons.
3. **Shine & Pós-Processamento Seletivo**:
   - Bloom seletivo em pirâmide de 5 níveis com atenuação por distância (respeitando o horizonte do **Distant Horizons 3.3.3**), *Sun Rim Lighting* opcional e *RGSS 4-tap Texture AA*.
4. **Performance & Frame Generation (LSR + FrameGen)**:
   - **LSR (Spatial Resolution Scaling)**: escala de resolução interna com nitidez adaptativa e proteção automática (`DH-Guard`) quando o Distant Horizons está ativo para evitar recorte de LODs.
   - **FrameGen**: modos `TFR_2X`, `TFR_3X`, `TFR_ADAPTIVE` e `INTERPOLATED_2X` com reprojeção separada para geometria e céu/LODs distantes do **Distant Horizons**, além de travas automáticas contra *ghosting* em giros 3D/roll, teleporte, pausas do **Dynamic FPS** e telas abertas.

## Compatibilidade Verificada (Modpack Optimized FPS)
- **Embeddium 0.3.31**, **Living Horizons 1.12.0**, **Distant Horizons 3.3.3**
- **Fauna Engine 1.3**, **Do A Barrel Roll**, **CameraOverhaul** (captura direta de `PoseStack` + detecção 3D de roll + exclusão de entidades Transform-Feedback no pass de sombras)
- **TimescaleLib**, **KnightLib**, **Diorama**, **Weatherglass** (sem `@Redirect` em `GameRenderer`/`LevelRenderer.renderLevel`)
- **ImmediatelyFast**, **GPUTape**, **BadOptimizations**, **Dynamic FPS** (`GlStateGuard` completo com VAO/scissor/texture units e limpeza segura na render thread)

## Uso
- Instale junto com **Living Horizons 1.12.0+ (1.20.1 Forge)** e **Embeddium 0.3.31+** (compatível com **Distant Horizons**).
- Pressione **`F8`** no jogo (ou abra pelo botão *Config* na tela de Mods) para ajustar cascatas, saturação RGB, bloom, LSR, FrameGen ou escolher um preset (`VANILLA_PLUS`, `BALANCED`, `CINEMATIC`, `LOW_END_FRAMEGEN`).

## Compilação
```powershell
$env:JAVA_HOME = "<caminho-para-jdk-17>"
.\gradlew.bat clean test build --no-daemon
```
O JAR final é gerado em `build/libs/livinghorizons_lux-1.0.0-1.20.1.jar`.
