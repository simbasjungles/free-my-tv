package io.freemytv;

/**
 * The pure rules of the CVTE boot home (issue #574, opção C): no Android classes, so they run in a plain JVM test
 * (tests/BootHomeTest.java). HomeMode asks these before it turns anything off.
 */
final class BootHome {
    private BootHome() {}

    /**
     * True when `cmd package query-activities -a MAIN -c HOME -c COMMON_HOME` lists this app's home activity and that
     * activity is direct-boot-aware. The line after the name carries the flags (`enabled=... directBootAware=...`).
     */
    static boolean ready(String queryOutput, String homeActivity) {
        if (queryOutput == null || homeActivity == null) return false;
        String[] lines = queryOutput.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("name=" + homeActivity)) continue;
            // The flags come a few lines below the name (after packageName, labels, processName), before the
            // ApplicationInfo block: read until that block or the next activity.
            for (int j = i + 1; j < lines.length; j++) {
                String line = lines[j].trim();
                if (line.startsWith("ApplicationInfo:") || line.startsWith("Activity #") || line.startsWith("name=")) break;
                if (line.contains("directBootAware=true")) return true;
            }
            return false;
        }
        return false;
    }

    /** Packages the CVTE family keeps (Vision's list, cleanyourtv): live TV, input and channel keys, factory menus. */
    static boolean keptOnCvte(String pkg) {
        return pkg.equals("com.cvte.tv.api.impl") || pkg.equals("com.cvte.tv.setting")
                || pkg.equals("com.cvte.tv.globalui") || pkg.equals("com.cvte.tvservice")
                || pkg.equals("com.cvte.fac.menu") || pkg.equals("com.droidlogic")
                || pkg.startsWith("com.droidlogic.");
    }
}
