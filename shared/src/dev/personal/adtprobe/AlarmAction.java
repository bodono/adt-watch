package dev.personal.adtprobe;

/** The only actions supported by an explicitly confirmed alarm request. */
public enum AlarmAction {
    ARM_STAY("Arm Stay", "WATCH ARM STAY"),
    DISARM("Disarm", "WATCH DISARM");

    private final String label;
    private final String widgetLabel;

    AlarmAction(String label, String widgetLabel) {
        this.label = label;
        this.widgetLabel = widgetLabel;
    }

    public String label() { return label; }
    public String widgetLabel() { return widgetLabel; }
}
