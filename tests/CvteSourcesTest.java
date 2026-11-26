package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The list the firmware returns on HQS32NKHM (issue #577), measured with a probe through the
 * cn.cvte.tv.api shared library: TV 21, AV 1 = 3, HDMI 1 = 9, HDMI 2 = 10, HDMI 3 = 11, all connected,
 * and MEDIA (the Android layer, 13) present internally but not in the list.
 */
public final class CvteSourcesTest {
    private static int assertions;

    /** The firmware's categories, as EnumInputSourceCategory constants. */
    private enum Category { INPUTSOURCE_TV, INPUTSOURCE_AV, INPUTSOURCE_HDMI, INPUTSOURCE_APP, INPUTSOURCE_SCART }

    /** The firmware's statuses, as EnumInputStatus constants. */
    private enum Status { INPUT_STATUS_CONNECTED, INPUT_STATUS_DISCONNECTED }

    /** Stands for com.cvte.tv.api.aidl.EntityInputSource: public fields, no getters, enums for category and status. */
    private static final class Entity {
        public String appPackageName;
        public Category category;
        public int id;
        public String name;
        public Status status;

        Entity(int id, String name, Category category, Status status) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.status = status;
        }
    }

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static Entity tv() { return new Entity(21, "TV", Category.INPUTSOURCE_TV, Status.INPUT_STATUS_CONNECTED); }
    private static Entity av1() { return new Entity(3, "AV 1", Category.INPUTSOURCE_AV, Status.INPUT_STATUS_CONNECTED); }
    private static Entity hdmi(int id, String name) {
        return new Entity(id, name, Category.INPUTSOURCE_HDMI, Status.INPUT_STATUS_CONNECTED);
    }
    private static Entity media() { return new Entity(13, "MEDIA", Category.INPUTSOURCE_APP, Status.INPUT_STATUS_CONNECTED); }

    private static List<Object> measured() {
        return new ArrayList<Object>(Arrays.asList(tv(), av1(), hdmi(9, "HDMI 1"), hdmi(10, "HDMI 2"),
                hdmi(11, "HDMI 3")));
    }

    /** One entity: its public fields become the values, enums by name (the firmware has no getters). */
    private void readsAnEntity() {
        CvteSources.Source s = CvteSources.from(tv());
        check(s.id == 21, "id must be read from the field");
        check("TV".equals(s.name), "name must be read from the field");
        check("INPUTSOURCE_TV".equals(s.category), "the category enum must come as its name");
        check("INPUT_STATUS_CONNECTED".equals(s.status), "the status enum must come as its name");
        check(s.appPackageName == null, "appPackageName must be read too");
        check(s.connected(), "INPUT_STATUS_CONNECTED means connected");
        check(s.liveTv(), "INPUTSOURCE_TV is the live TV source");
        check("live".equals(s.key()), "the tuner's drawer key is \"live\"");
    }

    private void keysThePorts() {
        check("hdmi1".equals(CvteSources.from(hdmi(9, "HDMI 1")).key()), "HDMI 1 keys as hdmi1");
        check("hdmi3".equals(CvteSources.from(hdmi(11, "HDMI 3")).key()), "HDMI 3 keys as hdmi3");
        check("av1".equals(CvteSources.from(av1()).key()), "AV 1 keys as av1");
        check(CvteSources.from(hdmi(9, "HDMI 1")).port() == 1, "the port number comes from the name");
        check(CvteSources.from(media()).key() == null, "the Android layer has no drawer key");
    }

    /** A port the firmware names without a number still needs a stable key: it falls back to the source id. */
    private void keysAPortWithoutANumber() {
        CvteSources.Source s = CvteSources.from(hdmi(42, "HDMI"));
        check("hdmi42".equals(s.key()), "a nameless port falls back to its id, got " + s.key());
    }

    private void listKeepsOrderAndDropsNulls() {
        List<Object> entities = new ArrayList<Object>(Arrays.asList(tv(), null, av1(), hdmi(9, "HDMI 1")));
        List<CvteSources.Source> list = CvteSources.list(entities);
        check(list.size() == 3, "nulls are skipped, the rest kept");
        check(list.get(0).id == 21 && list.get(1).id == 3 && list.get(2).id == 9, "the firmware's order is kept");
    }

    /** The drawer's rows: Live TV first (as allInputs does), then the ports in the firmware's order. */
    private void rowsAreLiveTvThenPorts() {
        List<CvteSources.Source> rows = CvteSources.rows(CvteSources.list(measured()));
        check(rows.size() == 5, "the five measured sources are rows, got " + rows.size());
        check("live".equals(rows.get(0).key()), "Live TV comes first");
        check("av1".equals(rows.get(1).key()), "then AV 1");
        check("hdmi1".equals(rows.get(2).key()) && "hdmi2".equals(rows.get(3).key())
                && "hdmi3".equals(rows.get(4).key()), "then the HDMI ports in order");
    }

    /** MEDIA is the Android layer, not a source to switch to; a category outside the Spec's three is not a row. */
    private void rowsLeaveOutWhatIsNotARow() {
        List<Object> entities = new ArrayList<Object>(Arrays.asList(media(), tv(),
                new Entity(7, "SCART", Category.INPUTSOURCE_SCART, Status.INPUT_STATUS_CONNECTED), av1()));
        List<CvteSources.Source> rows = CvteSources.rows(CvteSources.list(entities));
        check(rows.size() == 2, "only the tuner and the ports are rows, got " + rows.size());
        check("live".equals(rows.get(0).key()) && "av1".equals(rows.get(1).key()), "and in the right order");
    }

    private void disconnectedPortsAreNotConnected() {
        CvteSources.Source s = CvteSources.from(new Entity(9, "HDMI 1", Category.INPUTSOURCE_HDMI,
                Status.INPUT_STATUS_DISCONNECTED));
        check(!s.connected(), "a port without a signal is not connected");
    }

    /** AC6: without the firmware's library (as in this test JVM) the CVTE path stays off. */
    private void libraryAbsentKeepsTheCvtePathOff() {
        check(!CvteSources.firmwarePresent(),
                "the test JVM has no cn.cvte.tv.api, so the CVTE path must report it is off");
    }

    /** The source a drawer row opens: the tuner for the Live TV row, the port with that key for a port row. */
    private void findsTheSourceARowOpens() {
        List<CvteSources.Source> rows = CvteSources.rows(CvteSources.list(measured()));
        CvteSources.Source live = CvteSources.forRow(rows, true, "live");
        check(live != null && live.id == 21, "the Live TV row opens the tuner");
        CvteSources.Source hdmi2 = CvteSources.forRow(rows, false, "hdmi2");
        check(hdmi2 != null && hdmi2.id == 10, "the HDMI 2 row opens source 10");
        check(CvteSources.forRow(rows, false, "hdmi9") == null, "a key the firmware does not list opens nothing");
        check(CvteSources.forRow(null, true, "live") == null, "no list yet (still being read) opens nothing");
    }

    /**
     * The top button carries the chosen input's inputId under the button's own key, which is not a firmware key
     * (#580 review, blocking): the id has to decide, or the button falls through to the TIF passthrough request.
     */
    private void findsTheSourceByInputId() {
        List<CvteSources.Source> rows = CvteSources.rows(CvteSources.list(measured()));
        CvteSources.Source hdmi2 = CvteSources.forInputId(rows, "10");
        check(hdmi2 != null && "HDMI 2".equals(hdmi2.name), "inputId 10 opens HDMI 2");
        CvteSources.Source tuner = CvteSources.forInputId(rows, "21");
        check(tuner != null && tuner.liveTv(), "inputId 21 opens the tuner");
        check(CvteSources.forInputId(rows, "99") == null, "an id the firmware does not list opens nothing");
        check(CvteSources.forInputId(rows, null) == null, "no id opens nothing");
        check(CvteSources.forInputId(null, "10") == null, "no list yet opens nothing");
    }

    /** The precedence the top button needs: its input id first, then the key or the tuner (#580 re-review, nit 7). */
    private void resolvesByInputIdThenKey() {
        List<CvteSources.Source> rows = CvteSources.rows(CvteSources.list(measured()));
        CvteSources.Source button = CvteSources.resolve(rows, false, "top_live", "10");
        check(button != null && button.id == 10, "the button's inputId decides over the button's own key");
        CvteSources.Source drawer = CvteSources.resolve(rows, false, "hdmi1", "9");
        check(drawer != null && drawer.id == 9, "a drawer row agrees on id and key");
        CvteSources.Source fallback = CvteSources.resolve(rows, false, "hdmi1", "99");
        check(fallback != null && fallback.id == 9, "an id the firmware does not list falls back to the key");
        CvteSources.Source tuner = CvteSources.resolve(rows, true, "live", null);
        check(tuner != null && tuner.id == 21, "the Live TV row carries no id and resolves by category");
    }

    public static void main(String[] args) {
        CvteSourcesTest t = new CvteSourcesTest();
        t.readsAnEntity();
        t.keysThePorts();
        t.keysAPortWithoutANumber();
        t.listKeepsOrderAndDropsNulls();
        t.rowsAreLiveTvThenPorts();
        t.rowsLeaveOutWhatIsNotARow();
        t.disconnectedPortsAreNotConnected();
        t.libraryAbsentKeepsTheCvtePathOff();
        t.findsTheSourceARowOpens();
        t.findsTheSourceByInputId();
        t.resolvesByInputIdThenKey();
        System.out.println("CvteSourcesTest: ok (" + assertions + " assertions)");
    }
}
