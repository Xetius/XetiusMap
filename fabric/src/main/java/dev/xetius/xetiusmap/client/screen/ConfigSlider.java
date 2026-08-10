package dev.xetius.xetiusmap.client.screen;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * A slider over a numeric range, snapping to a step and labelling itself with the current value.
 *
 * <p>{@link AbstractSliderButton} works in a normalised 0..1 space, so this translates to and from
 * the real range and keeps the label in step.
 */
public final class ConfigSlider extends AbstractSliderButton {

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleFunction<String> format;
    private final DoubleConsumer onChange;

    public ConfigSlider(int x, int y, int width, int height, String label,
                        double min, double max, double step, double initial,
                        DoubleFunction<String> format, DoubleConsumer onChange) {
        super(x, y, width, height, Component.literal(label), normalise(initial, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.format = format;
        this.onChange = onChange;
        updateMessage();
    }

    private static double normalise(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
    }

    /** The real value the slider currently represents, snapped to the step. */
    public double value() {
        double raw = min + value * (max - min);
        if (step <= 0) {
            return raw;
        }
        double snapped = Math.round(raw / step) * step;
        return Math.max(min, Math.min(max, snapped));
    }

    @Override
    protected void updateMessage() {
        // The superclass constructor can reach this before the fields are assigned.
        if (format == null) {
            return;
        }
        setMessage(Component.literal(label + ": " + format.apply(value())));
    }

    @Override
    protected void applyValue() {
        if (onChange != null) {
            onChange.accept(value());
        }
    }
}
