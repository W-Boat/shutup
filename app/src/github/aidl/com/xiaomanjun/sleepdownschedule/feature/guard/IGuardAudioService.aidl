package com.xiaomanjun.sleepdownschedule.feature.guard;

// Deliberately bounded interface: no shell strings, package targets or arbitrary Binder calls.
interface IGuardAudioService {
    String playbackRoutes();
    void setMediaVolume(int index);
    void destroy();
}
