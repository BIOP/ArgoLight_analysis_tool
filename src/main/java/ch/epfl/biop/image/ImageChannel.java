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

/**
 * Object containing all information and methods related to one channel.
 */
public class ImageChannel {
    final private static String PROCESSED_FEATURE = "feature";
    final private int channelId;
    final private int imageWidth;
    final private int imageHeight;
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

    /**
     * Add values of FWHM for each ring as a list
     * @param fwhm
     */
    public void addFWHM(List<Double> fwhm) { this.ringsFWHM.addAll(fwhm); }

    /**
     * Add values of field distortion for each ring as a list
     * @param fieldDistortion
     */
    public void addFieldDistortion(List<Double> fieldDistortion) {
        this.ringsFieldDistortion.addAll(fieldDistortion);
    }

    /**
     * Add values of field uniformity for each ring as a list
     * @param fieldUniformity
     */
    public void addFieldUniformity(List<Double> fieldUniformity) {
        this.ringsFieldUniformity.addAll(fieldUniformity);
    }

    /**
     * Add ROIs for each detected ring as a list
     * @param rings
     */
    public void addGridRings(List<Roi> rings) {
        this.gridRings.addAll(rings);
    }

    /**
     * Add ROIs for each ideal ring as a list
     * @param idealRings
     */
    public void addIdealRings(List<Roi> idealRings) {
        this.idealGridRings.addAll(idealRings);
    }

    /**
     * set image rotation angle
     * @param rotationAngle
     */
    public void setRotationAngle(double rotationAngle){
        this.rotationAngle = rotationAngle;
    }

    /**
     * add a key-value pair for the channel
     * @param key
     * @param value
     */
    public void addKeyValue(String key, String value){
        this.keyValues.put(key, value);
    }

    /**
     * set the ROI central cross
     * @param cross
     */
    public void setCenterCross(Roi cross){ this.centerCross = cross; }

    /**
     * @return the central cross ROI
     */
    public Roi getCentralCross(){ return this.centerCross; }

    /**
     * @return FWHM values for each ring
     */
    public List<Double> getFWHM(){ return this.ringsFWHM; }

    /**
     * @return Field distortion values for each ring
     */
    public List<Double> getFieldDistortion(){return this.ringsFieldDistortion;}

    /**
     * @return Field uniformity values for each ring
     */
    public List<Double> getFieldUniformity(){return this.ringsFieldUniformity;}

    /**
     * @return the channel ID
     */
    public int getId(){ return this.channelId; }

    /**
     * @return the key-values pairs attached to the current channel
     */
    public Map<String,String> getKeyValues(){ return this.keyValues; }

    /**
     * @return the list of detected grid rings ROIs
     */
    public List<Roi> getGridRings(){ return this.gridRings; }

    /**
     * @return the list of ideal grid rings ROIs
     */
    public List<Roi> getIdealGridRings(){ return this.idealGridRings; }

    /**
     * @param imageName
     * @return a heatmap of the FWHM computed on each ring for the current channel
     */
    public ImagePlus getFWHMHeatMap(String imageName){
        ImagePlus img =  Tools.computeHeatMap(this.ringsFWHM, imageName+"_ch"+this.channelId+"_FWHM");
        img.setProperty(PROCESSED_FEATURE,"fwhm");
        return img;
    }

    /**
     * @param imageName
     * @return a heatmap of the field distortion computed on each ring for the current channel
     */
    public ImagePlus getFieldDistortionHeatMap(String imageName){
        ImagePlus img = Tools.computeHeatMap(this.ringsFieldDistortion, imageName+"_ch"+this.channelId+"_FieldDistortion");
        img.setProperty(PROCESSED_FEATURE,"field_distortion");
        return img;
    }

    /**
     * @param imageName
     * @return a heatmap of the field uniformity computed on each ring for the current channel
     */
    public ImagePlus getFieldUniformityHeatMap(String imageName){
        ImagePlus img =  Tools.computeHeatMap(this.ringsFieldUniformity, imageName+"_ch"+this.channelId+"_FieldUniformity");
        img.setProperty(PROCESSED_FEATURE,"field_uniformity");
        return img;
    }

    /**
     * @return a summary of computed metrics as a map (metric;value).
     */
    public Map<String, Double> channelSummary(){
        Map<String, Double> channelSummaryMap = new TreeMap<>();
        // general metrics
        channelSummaryMap.put("Channel",(double)this.channelId);
        channelSummaryMap.put("Rotation_angle__deg", this.rotationAngle);
        IJLogger.info("Channel "+this.channelId, "Rotation angle :"+this.rotationAngle);

        // get image centring statistics
        ImageStatistics crossStats = this.centerCross.getStatistics();

        channelSummaryMap.put("Cross_horizontal_shift__pix", crossStats.xCentroid - this.imageWidth/2);
        channelSummaryMap.put("Cross_vertical_shift__pix", crossStats.yCentroid - this.imageHeight/2);
        IJLogger.info("Channel "+this.channelId, "Horizontal cross shit :"+(crossStats.xCentroid - this.imageWidth/2));
        IJLogger.info("Channel "+this.channelId, "Vertical cross shit :"+(crossStats.yCentroid - this.imageHeight/2));

        // get field distortion summary
        double[] fieldDistortionStats = Tools.computeStatistics(this.ringsFieldDistortion);
        channelSummaryMap.put("Field_Distortion_avg__um", this.ringsFieldDistortion.isEmpty() ? -1 : fieldDistortionStats[0]);
        channelSummaryMap.put("Field_Distortion_std__um", this.ringsFieldDistortion.isEmpty() ? -1 : fieldDistortionStats[1]);
        channelSummaryMap.put("Field_Distortion_min__um", this.ringsFieldDistortion.isEmpty() ? -1 : fieldDistortionStats[2]);
        channelSummaryMap.put("Field_Distortion_max__um", this.ringsFieldDistortion.isEmpty() ? -1 : fieldDistortionStats[3]);

        IJLogger.info("Channel "+this.channelId, "Field distortion (avg, std, min, max) um :"
                +fieldDistortionStats[0] +", "
                +fieldDistortionStats[1] +", "
                +fieldDistortionStats[2] +", "
                +fieldDistortionStats[3] +", ");

        // get field uniformity summary
        double[] fieldUniformityStats = Tools.computeStatistics(this.ringsFieldUniformity);
        channelSummaryMap.put("Field_Uniformity_avg", this.ringsFieldUniformity.isEmpty() ? -1 : fieldUniformityStats[0]);
        channelSummaryMap.put("Field_Uniformity_std", this.ringsFieldUniformity.isEmpty() ? -1 : fieldUniformityStats[1]);
        channelSummaryMap.put("Field_Uniformity_min", this.ringsFieldUniformity.isEmpty() ? -1: fieldUniformityStats[2]);
        channelSummaryMap.put("Field_Uniformity_max", this.ringsFieldUniformity.isEmpty() ? -1 : fieldUniformityStats[3]);

        IJLogger.info("Channel "+this.channelId, "Field uniformity (avg, std, min, max) um :"
                +fieldUniformityStats[0] +", "
                +fieldUniformityStats[1] +", "
                +fieldUniformityStats[2] +", "
                +fieldUniformityStats[3] +", ");

        // get FWHM summary
        double[] fwhmStats = Tools.computeStatistics(this.ringsFWHM);
        channelSummaryMap.put("Field_FWHM_avg__um", this.ringsFWHM.isEmpty() ? -1 : fwhmStats[0]);
        channelSummaryMap.put("Field_FWHM_std__um", this.ringsFWHM.isEmpty() ? -1: fwhmStats[1]);
        channelSummaryMap.put("Field_FWHM_min__um", this.ringsFWHM.isEmpty() ? -1 : fwhmStats[2]);
        channelSummaryMap.put("Field_FWHM_max__um", this.ringsFWHM.isEmpty() ? -1 : fwhmStats[3]);

        IJLogger.info("Channel "+this.channelId, "FWHM (avg, std, min, max) um :"
                +fwhmStats[0] +", "
                +fwhmStats[1] +", "
                +fwhmStats[2] +", "
                +fwhmStats[3] +", ");

        return channelSummaryMap;
    }
}
