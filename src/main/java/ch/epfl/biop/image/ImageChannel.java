package ch.epfl.biop.image;

import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.utils.Tools;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ImageChannel {
    final private static String PROCESSED_FEATURE = "feature";
    private int channelId;
    private int imageWidth;
    private int imageHeight;
    private List<Double> ringsFWHM = new ArrayList<>();
    private List<Double> ringsFieldDistortion = new ArrayList<>();
    private List<Double> ringsFieldUniformity = new ArrayList<>();
    private List<Roi> gridRings = new ArrayList<>();
    private List<Roi> idealGridRings = new ArrayList<>();
    private double rotationAngle;

    private Map<String, String> keyValues = new TreeMap<>();
    private Roi centerCross;

    public ImageChannel(int id, int width, int height){
        this.channelId = id;
        this.imageWidth = width;
        this.imageHeight = height;
    }

    public void addFWHM(List<Double> fwhm) {
        this.ringsFWHM.addAll(fwhm);
    }

    public void addFieldDistortion(List<Double> fieldDistortion) {
        this.ringsFieldDistortion.addAll(fieldDistortion);
    }

    public void addFieldUniformity(List<Double> fieldUniformity) {
        this.ringsFieldUniformity.addAll(fieldUniformity);
    }

    public void addGridRings(List<Roi> rings) {
        this.gridRings.addAll(rings);
    }

    public void addIdealRings(List<Roi> idealRings) {
        this.idealGridRings.addAll(idealRings);
    }

    public void setRotationAngle(double rotationAngle){
        this.rotationAngle = rotationAngle;
    }

    public void addKeyValue(String key, String value){
        this.keyValues.put(key, value);
    }

    public void setCenterCross(Roi cross){
        this.centerCross = cross;
    }

    public List<Double> getFWHM(){ return this.ringsFWHM; }

    public List<Double> getFieldDistortion(){return this.ringsFieldDistortion;}

    public List<Double> getFieldUniformity(){return this.ringsFieldUniformity;}

    public int getId(){ return this.channelId; }

    public Map<String,String> getKeyValues(){ return this.keyValues; }

    public List<Roi> getGridRings(){
        return this.gridRings;
    }

    public List<Roi> getIdealGridRings(){
        return this.idealGridRings;
    }

    public ImagePlus getFWHMHeatMap(String imageName){
        ImagePlus img =  Tools.computeHeatMap(this.ringsFWHM, imageName+"_ch"+this.channelId+"_FWHM");
        img.setProperty(PROCESSED_FEATURE,"fwhm");
        return img;
    }
    public ImagePlus getFieldDistortionHeatMap(String imageName){
        ImagePlus img = Tools.computeHeatMap(this.ringsFieldDistortion, imageName+"_ch"+this.channelId+"_FieldDistortion");
        img.setProperty(PROCESSED_FEATURE,"field_distortion");
        return img;
    }
    public ImagePlus getFieldUniformityHeatMap(String imageName){
        ImagePlus img =  Tools.computeHeatMap(this.ringsFieldUniformity, imageName+"_ch"+this.channelId+"_FieldUniformity");
        img.setProperty(PROCESSED_FEATURE,"field_uniformity");
        return img;
    }


    public Map<String, Double> channelSummary(){
        Map<String, Double> channelSummaryMap = new TreeMap<>();
        channelSummaryMap.put("Channel",(double)this.channelId);
        channelSummaryMap.put("Rotation_angle__°", this.rotationAngle);
        IJLogger.info("Channel "+this.channelId, "Rotation angle :"+this.rotationAngle);

        ImageStatistics crossStats = this.centerCross.getStatistics();

        channelSummaryMap.put("Cross_horizontal_shift__pix", crossStats.xCentroid - this.imageWidth/2);
        channelSummaryMap.put("Cross_vertical_shift__pix", crossStats.yCentroid - this.imageHeight/2);
        IJLogger.info("Channel "+this.channelId, "Horizontal cross shit :"+(crossStats.xCentroid - this.imageWidth/2));
        IJLogger.info("Channel "+this.channelId, "Vertical cross shit :"+(crossStats.yCentroid - this.imageHeight/2));

        double[] fieldDistortionStats = Tools.computeStatistics(this.ringsFieldDistortion);
        channelSummaryMap.put("Field_Distortion_avg__um", fieldDistortionStats[0]);
        channelSummaryMap.put("Field_Distortion_std__um", fieldDistortionStats[1]);
        channelSummaryMap.put("Field_Distortion_min__um", fieldDistortionStats[2]);
        channelSummaryMap.put("Field_Distortion_max__um", fieldDistortionStats[3]);

        double[] fieldUniformityStats = Tools.computeStatistics(this.ringsFieldUniformity);
        channelSummaryMap.put("Field_Uniformity_avg", fieldUniformityStats[0]);
        channelSummaryMap.put("Field_Uniformity_std", fieldUniformityStats[1]);
        channelSummaryMap.put("Field_Uniformity_min", fieldUniformityStats[2]);
        channelSummaryMap.put("Field_Uniformity_max", fieldUniformityStats[3]);

        double[] fwhmStats = Tools.computeStatistics(this.ringsFWHM);
        channelSummaryMap.put("Field_FWHM_avg__um", fwhmStats[0]);
        channelSummaryMap.put("Field_FWHM_std__um", fwhmStats[1]);
        channelSummaryMap.put("Field_FWHM_min__um", fwhmStats[2]);
        channelSummaryMap.put("Field_FWHM_max__um", fwhmStats[3]);

        IJLogger.info("Channel "+this.channelId, "Field distortion (avg, std, min, max) um :"
                +fieldDistortionStats[0] +", "
                +fieldDistortionStats[1] +", "
                +fieldDistortionStats[2] +", "
                +fieldDistortionStats[3] +", ");
        IJLogger.info("Channel "+this.channelId, "Field uniformity (avg, std, min, max) um :"
                +fieldUniformityStats[0] +", "
                +fieldUniformityStats[1] +", "
                +fieldUniformityStats[2] +", "
                +fieldUniformityStats[3] +", ");
        IJLogger.info("Channel "+this.channelId, "FWHM (avg, std, min, max) um :"
                +fwhmStats[0] +", "
                +fwhmStats[1] +", "
                +fwhmStats[2] +", "
                +fwhmStats[3] +", ");

        return channelSummaryMap;
    }
}
