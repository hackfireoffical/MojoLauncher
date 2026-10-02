package net.kdt.pojavlaunch.game.renderer.impl;

import static android.os.Build.VERSION.SDK_INT;

import android.content.Context;

import net.kdt.pojavlaunch.game.renderer.RenderSpec;
import net.kdt.pojavlaunch.game.renderer.def.Renderers;
import net.kdt.pojavlaunch.utils.GpuUtils;

import java.io.File;
import java.util.Map;

import git.artdeell.mojo.R;
import git.artdeell.mojoexec.MojoExec;

/**
 * MobileGlues renderer.
 *
 * MobileGlues translates desktop OpenGL to OpenGL ES 3.x and is intended
 * for Minecraft Java Edition on Android.
 */
public class MobileGluesRenderSpec implements RenderSpec {
    private static final String LIBRARY = "libmobileglues.so";

    @Override
    public boolean compatibleDevice(Context context) {
        if (SDK_INT < 21) return false;
        if (!new File(net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR, LIBRARY).exists()) return false;
        return GpuUtils.getGlInfo().glesMajorVersion >= 3;
    }

    @Override
    public String name() {
        return "MobileGlues";
    }

    @Override
    public int displayName() {
        return R.string.mcl_setting_renderer_mobileglues;
    }

    @Override
    public String tag() {
        return Renderers.MOBILEGLUES_RENDERER;
    }

    @Override
    public String library() {
        return LIBRARY;
    }

    @Override
    public void setupEnvironment(Context context, Map<String, String> envMap) {
        envMap.put("LIBGL_ES", "3");
    }

    @Override
    public boolean setupRenderer() {
        return MojoExec.prepareEgl(LIBRARY, true, false, 3);
    }
}
