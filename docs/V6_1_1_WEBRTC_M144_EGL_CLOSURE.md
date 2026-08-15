# v6.1.1 — Exact WebRTC M144 EGL Closure

## Dependency

```text
io.github.webrtc-sdk:android:144.7559.09
source closure: webrtc-sdk/webrtc @ b1800a61db8320af5c14456c13622d8b85b1ed39
```

## Static result

Exact source `sdk/android/api/org/webrtc/SurfaceViewRenderer.java`:

```java
public void init(EglBase.Context sharedContext, RendererCommon.RendererEvents rendererEvents) {
  init(sharedContext, rendererEvents, EglBase.CONFIG_PLAIN, new GlRectDrawer());
}
```

Exact source `sdk/android/api/org/webrtc/EglBase.java` builds `CONFIG_PLAIN` with:

```text
EGL_RED_SIZE   8
EGL_GREEN_SIZE 8
EGL_BLUE_SIZE  8
```

Therefore the current two-argument renderer path statically requests an RGB888 EGL config. This is a statement about the request path, not yet a substitute for a true-device query of the selected config.

## Runtime closure

`GfnEglConfigProbe.queryCurrentEgl14()` is read-only. It queries the current renderer EGL state for:

```text
configId
red
green
blue
alpha
renderableType
surfaceType
```

No call chooses a new EGLConfig, creates a replacement context/surface, changes current context, or changes WebRTC `CONFIG_PLAIN`.

Expected log:

```text
GfnHevc10Bit phase=EGL_CONFIG view=<id>
success=true
configId=<id>
red=<n> green=<n> blue=<n> alpha=<n>
tenBitRgbTarget=<true|false>
```

Runtime 8/8/8 is expected from the static request but is deliberately not asserted before true-device evidence.

## Why no RGB10A2 change yet

v6.1.1 Stage A/B is diagnostic isolation. If the server SPS is not actually 10-bit, changing the renderer would confound the root cause. If the runtime config is already >=10bpc, changing it would also be unjustified. Renderer changes are therefore gated on collected evidence.
