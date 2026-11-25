package io.freemytv;

/** The CVTE boot-home rules (BootHome), run in a plain JVM: tools/test-boot-home.sh. */
public class BootHomeTest {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        String home = "io.freemytv.HomeActivity";
        String ready = "    name=io.freemytv.HomeActivity\n"
                + "      enabled=true exported=true directBootAware=true\n"
                + "        name=io.freemytv.App\n";
        String notAware = "    name=io.freemytv.HomeActivity\n"
                + "      enabled=true exported=true directBootAware=false\n";
        String stockOnly = "    name=com.cvte.tv.launcher.brhq.module.home.LaunchActivity\n"
                + "      enabled=true exported=true directBootAware=true\n";
        String none = "";

        check("pronto quando o HomeActivity casa com directBootAware=true", BootHome.ready(ready, home));
        check("não pronto sem directBootAware (caso de 24/11)", !BootHome.ready(notAware, home));
        check("não pronto quando o HomeActivity não casa com o intent", !BootHome.ready(stockOnly, home));
        check("não pronto sem saída", !BootHome.ready(none, home));
        check("não pronto com null", !BootHome.ready(null, home));
        check("ignora um pacote parecido", !BootHome.ready(ready, "io.freemytv.Home"));
        String real = "  Activity #1:\n    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"
                + "    ActivityInfo:\n      name=io.freemytv.HomeActivity\n      packageName=io.freemytv\n"
                + "      labelRes=0x0 nonLocalizedLabel=Free My TV icon=0x0 banner=0x0\n      processName=io.freemytv:home\n"
                + "      enabled=true exported=true directBootAware=true\n      taskAffinity=null targetActivity=null\n"
                + "      ApplicationInfo:\n        name=io.freemytv.App\n";
        check("pronto com a saída real do TV (flags 4 linhas depois do nome)", BootHome.ready(real, home));
        String realNotAware = real.replace("directBootAware=true", "directBootAware=false");
        check("não pronto com a saída real e directBootAware=false", !BootHome.ready(realNotAware, home));
        check("pronto mesmo com outras linhas antes", BootHome.ready("Activity #0:\n" + ready, home));

        check("mantém vision: com.cvte.tv.setting", BootHome.keptOnCvte("com.cvte.tv.setting"));
        check("mantém vision: com.cvte.tv.api.impl", BootHome.keptOnCvte("com.cvte.tv.api.impl"));
        check("mantém vision: com.cvte.tv.globalui", BootHome.keptOnCvte("com.cvte.tv.globalui"));
        check("mantém vision: com.droidlogic.tvinput", BootHome.keptOnCvte("com.droidlogic.tvinput"));
        check("mantém vision: com.cvte.fac.menu", BootHome.keptOnCvte("com.cvte.fac.menu"));
        check("não protege o launcher de fábrica", !BootHome.keptOnCvte("com.cvte.tv.launcher.brhq"));
        check("não protege um app comum", !BootHome.keptOnCvte("com.netflix.mediaclient"));

        if (failures > 0) {
            System.out.println(failures + " falha(s)");
            System.exit(1);
        }
        System.out.println("todos os testes passaram");
    }
}
