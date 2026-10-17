package com.kinetic.model.catalog;

import com.kinetic.model.domain.ModelScanWarning;
import java.util.List;
import java.util.Objects;

public record ReloadResult(ReloadStatus status, int modelCount, int errorCount,
                           String message,
                           List<ModelScanWarning> warnings) {
    public ReloadResult {
        Objects.requireNonNull(status, "status");
        message = Objects.requireNonNullElse(message, "");
        warnings = List.copyOf(warnings);
    }

    public ReloadResult(ReloadStatus status, int modelCount, int errorCount,
                        String message) {
        this(status, modelCount, errorCount, message, List.of());
    }

    public int warningCount() {
        return warnings.stream()
                .mapToInt(ModelScanWarning::occurrences)
                .sum();
    }

}
