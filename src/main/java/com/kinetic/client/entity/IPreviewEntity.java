package com.kinetic.client.entity;

import com.kinetic.client.gui.PreviewAnimationInfo;
import org.jetbrains.annotations.NotNull;

public interface IPreviewEntity {
    @NotNull
    PreviewAnimationInfo getPreviewInfo();

    void setAllowEmitting(boolean allow);
}
