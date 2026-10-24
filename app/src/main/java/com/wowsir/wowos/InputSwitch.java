package com.wowsir.wowos;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Switches the TV's input (HDMI / AV / antenna) through HiSilicon's own TvManager,
 * the same path the stock apps use. All access is reflective: WOW OS compiles and
 * runs as an ordinary app, and these calls only succeed when it is installed as a
 * system app (so it is granted com.hisilicon.permission.COMMON_PERMISSION).
 *
 * Nothing here is device-specific beyond the HiSilicon class names; the source
 * names are discovered from the TV's own EnumSourceIndex at runtime.
 */
final class InputSwitch {

    /** A selectable input, in the order WOW OS shows it. */
    static final class Input {
        final String label;        // what the user sees: "HDMI 1"
        final String enumName;     // EnumSourceIndex constant: "HDMI1"
        final int iconRes;
        Input(String label, String enumName, int iconRes) {
            this.label = label;
            this.enumName = enumName;
            this.iconRes = iconRes;
        }
    }

    private static final String C_TVMANAGER = "com.hisilicon.android.tv.TvManager";
    private static final String C_ENUM_SOURCE = "com.hisilicon.android.tvapi.constant.EnumSourceIndex";

    /** Friendly label + candidate EnumSourceIndex names (first that exists wins). */
    private static final Object[][] CANDIDATES = {
            {"TV", R.drawable.ic_tv, new String[]{"ATV", "DTV", "TV"}},
            {"HDMI 1", R.drawable.ic_hdmi, new String[]{"HDMI1", "HDMI_1", "HDMI"}},
            {"HDMI 2", R.drawable.ic_hdmi, new String[]{"HDMI2", "HDMI_2"}},
            {"HDMI 3", R.drawable.ic_hdmi, new String[]{"HDMI3", "HDMI_3"}},
            {"AV", R.drawable.ic_plug, new String[]{"AV", "AV1", "CVBS1", "CVBS"}},
            {"AV 2", R.drawable.ic_plug, new String[]{"AV2", "CVBS2"}},
    };

    private InputSwitch() { }

    // ------------------------------------------------------------------ availability

    private static boolean classesPresent() {
        try {
            Class.forName(C_TVMANAGER);
            Class.forName(C_ENUM_SOURCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when this build can actually drive the inputs (system install on this TV). */
    static boolean available(Context c) {
        if (!classesPresent()) return false;
        try {
            return sourceManager(c) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Inputs that both exist on this TV and WOW OS knows a friendly name for. */
    @SuppressWarnings("unchecked")
    static List<Input> inputs() {
        List<Input> out = new ArrayList<>();
        Map<String, Object> enumByName = enumConstants();
        if (enumByName.isEmpty()) return out;
        for (Object[] row : CANDIDATES) {
            String label = (String) row[0];
            int icon = (Integer) row[1];
            for (String name : (String[]) row[2]) {
                if (enumByName.containsKey(name)) {
                    out.add(new Input(label, name, icon));
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ switching

    /**
     * Switches to the named input. Returns null on success, or a short reason on failure.
     */
    static String select(Context c, String enumName) {
        if (!classesPresent()) {
            return "Input switching needs WOW OS installed as a system app";
        }
        try {
            Object source = sourceManager(c);
            if (source == null) return "The TV service didn't respond";
            Object value = enumConstants().get(enumName);
            if (value == null) return "This TV has no " + enumName + " input";

            Class<?> enumCls = Class.forName(C_ENUM_SOURCE);
            // Prefer selectSource(EnumSourceIndex); fall back to an int-taking variant.
            Method m = findMethod(source.getClass(),
                    new String[]{"selectSource", "changeSource", "setSource", "switchSource"}, enumCls);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(source, value);
                return null;
            }
            // Some builds take the ordinal/code as an int.
            int code = sourceCode(value);
            m = findMethod(source.getClass(),
                    new String[]{"selectSource", "changeSource", "setSource", "switchSource"}, int.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(source, code);
                return null;
            }
            return "Couldn't find the input-switch call on this TV";
        } catch (Throwable t) {
            String msg = t.getCause() != null ? t.getCause().getMessage() : t.getMessage();
            return msg == null ? "Input switch failed" : msg;
        }
    }

    /** Current input's EnumSourceIndex name, or null if unknown. */
    static String current(Context c) {
        try {
            Object source = sourceManager(c);
            if (source == null) return null;
            Method get = findMethod(source.getClass(),
                    new String[]{"getCurSourceId", "getCurSource", "getCurrentSource"}, null);
            if (get == null) return null;
            get.setAccessible(true);
            Object cur = get.invoke(source);
            if (cur == null) return null;
            if (cur.getClass().isEnum()) return ((Enum<?>) cur).name();
            // int code: match back to a name.
            if (cur instanceof Integer) {
                for (Map.Entry<String, Object> e : enumConstants().entrySet()) {
                    if (sourceCode(e.getValue()) == (Integer) cur) return e.getKey();
                }
            }
            return String.valueOf(cur);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    private static Object tvManager(Context c) throws Exception {
        Class<?> tvm = Class.forName(C_TVMANAGER);
        // Try the common singleton shapes in turn.
        for (String name : new String[]{"getInstance", "getTvManager", "getDefault"}) {
            try {
                Method m = tvm.getMethod(name);
                return m.invoke(null);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                // method exists but needs a Context — try that below
            }
            try {
                Method m = tvm.getMethod(name, Context.class);
                return m.invoke(null, c);
            } catch (NoSuchMethodException ignored) { }
        }
        // Last resort: a public no-arg constructor.
        return tvm.newInstance();
    }

    private static Object sourceManager(Context c) throws Exception {
        Object mgr = tvManager(c);
        if (mgr == null) return null;
        Method get = findMethod(mgr.getClass(),
                new String[]{"getTvSource", "getSourceManager", "getTvSourceManager", "getSource"}, null);
        if (get == null) return null;
        get.setAccessible(true);
        return get.invoke(mgr);
    }

    /** All EnumSourceIndex constants by name. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> enumConstants() {
        Map<String, Object> map = new LinkedHashMap<>();
        try {
            Class<?> enumCls = Class.forName(C_ENUM_SOURCE);
            Object[] values = enumCls.getEnumConstants();
            if (values != null) {
                for (Object v : values) map.put(((Enum<?>) v).name(), v);
            }
        } catch (Throwable ignored) { }
        return map;
    }

    /** The integer the service uses for an enum value (ordinal, or a getCode/getValue). */
    private static int sourceCode(Object enumValue) {
        for (String name : new String[]{"getSourceIndex", "getValue", "getCode", "toInt"}) {
            try {
                Method m = enumValue.getClass().getMethod(name);
                Object r = m.invoke(enumValue);
                if (r instanceof Integer) return (Integer) r;
            } catch (Throwable ignored) { }
        }
        return ((Enum<?>) enumValue).ordinal();
    }

    private static Method findMethod(Class<?> cls, String[] names, Class<?> arg) {
        List<String> wanted = Arrays.asList(names);
        for (Method m : cls.getMethods()) {
            if (!wanted.contains(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (arg == null) {
                if (p.length == 0) return m;
            } else if (p.length == 1 && p[0].isAssignableFrom(arg)) {
                return m;
            } else if (p.length == 1 && arg == int.class && (p[0] == int.class || p[0] == Integer.class)) {
                return m;
            }
        }
        return null;
    }

    /** Fallback for non-system installs: open the TV's own Source menu if it exists. */
    static boolean openStockSourceMenu(Context c) {
        String[] actions = {
                "com.vstv.tv.intent.action.SETUP_INPUTS",
                "com.hisilicon.action.SOURCE_LIST",
        };
        for (String a : actions) {
            Intent i = new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (i.resolveActivity(c.getPackageManager()) != null) {
                    c.startActivity(i);
                    return true;
                }
            } catch (Throwable ignored) { }
        }
        return false;
    }
}
