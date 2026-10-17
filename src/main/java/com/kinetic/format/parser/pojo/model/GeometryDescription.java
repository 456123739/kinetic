package com.kinetic.format.parser.pojo.model;

import com.google.gson.annotations.SerializedName;

public class GeometryDescription {
    public String identifier = "";

    @SerializedName("texture_height")
    public float textureHeight;

    @SerializedName("texture_width")
    public float textureWidth;

    @SerializedName("visible_bounds_height")
    public float visibleBoundsHeight;

    @SerializedName("visible_bounds_width")
    public float visibleBoundsWidth;

    @SerializedName("visible_bounds_offset")
    public float[] visibleBoundsOffset;

    @SerializedName("kinetic_height_scale")
    public float kineticHeightScale = 0.7f;

    @SerializedName("kinetic_width_scale")
    public float kineticWidthScale = 0.7f;

    @SerializedName("kinetic_extra_info")
    public ExtraInfo kineticExtraInfo;
}
