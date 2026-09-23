package wallet.send;

/**
 * Release model for one row or section in the transaction confirmation dialog.
 * All visual resources are supplied by the presenter so the dialog renderer
 * does not infer meaning from display text.
 */
public final class ConfirmationDisplayItem {
    private final String label;
    private final String value;
    private final String sectionTitle;
    private final boolean important;
    private final boolean dividerBefore;
    private final int iconResId;
    private final boolean sectionHeader;
    private final boolean warning;

    public ConfirmationDisplayItem(
            String label,
            String value,
            String sectionTitle,
            boolean important,
            boolean dividerBefore,
            int iconResId,
            boolean sectionHeader,
            boolean warning) {
        this.label = label;
        this.value = value;
        this.sectionTitle = sectionTitle;
        this.important = important;
        this.dividerBefore = dividerBefore;
        this.iconResId = iconResId;
        this.sectionHeader = sectionHeader;
        this.warning = warning;
    }

    public String getLabel() {
        return label;
    }

    public String getValue() {
        return value;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public boolean isImportant() {
        return important;
    }

    public boolean hasDividerBefore() {
        return dividerBefore;
    }

    public int getIconResId() {
        return iconResId;
    }

    public boolean isSectionHeader() {
        return sectionHeader;
    }

    public boolean isWarning() {
        return warning;
    }
}
