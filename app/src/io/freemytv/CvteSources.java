package io.freemytv;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * CVTE boards (HiSilicon) register no TvInputService, so TvInputManager sees no input at all and the drawer comes
 * up empty (issue #577). The firmware keeps its own list in the cn.cvte.tv.api shared library: EntityInputSource
 * carries public fields id, name, category, status and appPackageName (no getters), and sources are switched with
 * eventSystemInputSourceSetInputSource(id).
 *
 * This class turns that list into the drawer's rows, with no Android and no vendor class: the fields come by
 * reflection, the values as plain strings, so it can be tested on its own.
 */
final class CvteSources {
    /** The categories the Spec makes rows of (RF1); everything else, MEDIA included, stays out. */
    static final String TV = "INPUTSOURCE_TV", AV = "INPUTSOURCE_AV", HDMI = "INPUTSOURCE_HDMI";
    static final String CONNECTED = "INPUT_STATUS_CONNECTED";

    private CvteSources() {}

    /**
     * Whether the firmware's AIDL library (cn.cvte.tv.api) is on this TV: it is what the CVTE path calls, and
     * without it the path must stay off and today's behaviour stand (AC6). Answered once, since a library cannot
     * appear while the process lives; a library that is there but broken counts as absent, so the home screen
     * never goes down with it.
     */
    static boolean firmwarePresent() {
        if (present == null) {
            try {
                Class.forName("com.cvte.tv.api.aidl.ITvApiManager");
                present = Boolean.TRUE;
            } catch (Throwable t) {
                present = Boolean.FALSE;
            }
        }
        return present.booleanValue();
    }

    private static Boolean present;

    static final class Source {
        public final int id;
        public final String name, category, status, appPackageName;

        Source(int id, String name, String category, String status, String appPackageName) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.status = status;
            this.appPackageName = appPackageName;
        }

        boolean connected() {
            return CONNECTED.equals(status);
        }

        boolean liveTv() {
            return TV.equals(category);
        }

        /** A port the drawer can open: an HDMI or an AV input. */
        boolean isPort() {
            return HDMI.equals(category) || AV.equals(category);
        }

        /** The port number the firmware put in the name ("HDMI 2" -> 2), 0 when the name has no number. */
        int port() {
            if (name == null) return 0;
            int end = name.length();
            while (end > 0 && Character.isDigit(name.charAt(end - 1))) end--;
            if (end == name.length()) return 0;
            try {
                return Integer.parseInt(name.substring(end));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * The drawer's key, the one Settings > Inputs saves ("live", "hdmi1"...). A port the firmware names without
         * a number falls back to the source id, which is at least stable on that firmware.
         */
        String key() {
            if (liveTv()) return "live";
            String prefix = HDMI.equals(category) ? "hdmi" : AV.equals(category) ? "av" : null;
            if (prefix == null) return null;
            int port = port();
            return prefix + (port > 0 ? port : id);
        }
    }

    /** One entity of the firmware's list, read through its public fields. */
    static Source from(Object entity) {
        if (entity == null) return null;
        int id = 0;
        String name = null, category = null, status = null, appPackageName = null;
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(entity);
                } catch (ReflectiveOperationException | SecurityException e) {
                    continue;
                }
                String n = f.getName();
                if ("id".equals(n)) {
                    if (value instanceof Integer) id = (Integer) value;
                } else if ("name".equals(n)) {
                    name = (String) value;
                } else if ("category".equals(n)) {
                    category = value == null ? null : String.valueOf(value);
                } else if ("status".equals(n)) {
                    status = value == null ? null : String.valueOf(value);
                } else if ("appPackageName".equals(n)) {
                    appPackageName = (String) value;
                }
            }
        }
        return new Source(id, name, category, status, appPackageName);
    }

    /** The firmware's list as sources, in its order; a null entry is skipped. */
    static List<Source> list(List<?> entities) {
        List<Source> out = new ArrayList<>();
        if (entities == null) return out;
        for (Object entity : entities) {
            Source s = from(entity);
            if (s != null) out.add(s);
        }
        return out;
    }

    /**
     * The drawer's rows: Live TV first, as allInputs() does, then the ports in the firmware's order. What is not a
     * row (MEDIA, the Android layer, and any category outside the Spec's three) is left out.
     */
    static List<Source> rows(List<Source> sources) {
        List<Source> out = new ArrayList<>();
        if (sources == null) return out;
        for (Source s : sources) if (s != null && s.liveTv()) out.add(s);
        for (Source s : sources) if (s != null && s.isPort()) out.add(s);
        return out;
    }
}
