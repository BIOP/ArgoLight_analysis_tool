package ch.epfl.biop;

import ij.gui.Roi;

import java.util.List;
import java.util.Map;

public class ImageChannel {

    private int channelId;
    private List<Double> ringsFWHM;
    private List<Double> ringsFieldDistortion;
    private List<Double> ringsFieldUniformity;
    private List<Roi> gridRings;
    private List<Roi> idealGridRings;
    private double rotationAngle;
    private List<String> tags;
    private Map<String, String> keyValues;


}
