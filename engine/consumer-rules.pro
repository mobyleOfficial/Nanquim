-keepclasseswithmembernames class com.mobyle.nanquim.engine.Engine {
    native <methods>;
}
-keepclassmembers class com.mobyle.nanquim.engine.Engine {
    private void onNativeGpuInfo(int, java.lang.String);
    private void onNativeStats(int, java.lang.String);
    private void onNativeError(int, java.lang.String, boolean);
    private void onNativeSceneRendered(int, int, int, byte[], java.lang.String);
}
-keepclassmembers class * implements com.mobyle.nanquim.engine.BrushPad$Companion$ProgressListener {
    boolean onProgress(float);
}
