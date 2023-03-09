package ch.epfl.biop.image;

import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.utils.Tools;
import ij.ImagePlus;
import ij.gui.Roi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageFile {
    private final Pattern pattern482 = Pattern.compile("(?<microscope>.*)_o(?<objective>.*)_z(?<zoom>.*)_(?<immersion>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_?(?<series>.*)?\\.(?<extension>.*)");
    private final String imgName;
    private final String imgNameWithoutExtension;
    private String microscope;
    private String objective;
    private String immersionMedium;
    private String argoSlideName;
    private String acquisitionDate;
    private String zoomFactor;
    private String argoSlidePattern;
    private final ImagePlus image;
    private List<String> tags = new ArrayList<>();
    private Map<String, String> keyValues = new HashMap<>();
    private final long id;
    private List<List<Double>> pccValues = new ArrayList<>();
    public List<ImageChannel> channels = new ArrayList<>();

    public ImageFile(ImagePlus imp, long id){
        this.image = imp;
        this.id = id;
        this.imgName = imp.getTitle();
        this.imgNameWithoutExtension = getNameWithoutExtension(this.imgName);
        parseImageName(this.imgName);
    }

    public void addChannel(ImageChannel imageChannel){
        channels.add(imageChannel);
    }
    public void addTags(String... tags){
        this.tags.addAll(Arrays.asList(tags));
    }
    public void addKeyValue(String key, String value){
        this.keyValues.put(key, value);
    }
    public int getNChannels(){ return this.channels.size(); }
    public String getImgNameWithoutExtension() { return this.imgNameWithoutExtension; }
    public Map<String,String> getKeyValues(){ return this.keyValues; }
    public long getId(){
        return this.id;
    }
    public ImagePlus getImage(){ return this.image; }
    public List<String> getTags(){
        return this.tags;
    }
    public List<List<Double>> getPCC(){ return this.pccValues; }
    public String getArgoSlideName(){ return this.argoSlideName; }

    public ImageChannel getChannel(int id){
        if(id >= this.channels.size()) {
            IJLogger.error("Get image channel", "You try to access to channel "+id+ " that doesn't exists");
            return null;
        }
        return this.channels.get(id);
    }

    private void parseImageName(String imgName){
        Matcher matcher = pattern482.matcher(imgName);

        if(matcher.find()) {
            this.microscope = matcher.group("microscope");
            this.objective = matcher.group("objective");
            this.zoomFactor = matcher.group("zoom");
            this.immersionMedium = matcher.group("immersion");
            this.argoSlideName = matcher.group("argoslide");
            this.argoSlidePattern = matcher.group("pattern");
            this.acquisitionDate = matcher.group("date");

            this.keyValues.put("Microscope", this.microscope);
            this.keyValues.put("Objective", this.objective);
            this.keyValues.put("Immersion", this.immersionMedium);
            this.keyValues.put("Zoom", this.zoomFactor);
            this.keyValues.put("ArgoSlide_name", this.argoSlideName);
            this.keyValues.put("ArgoSlide_pattern", this.argoSlidePattern);
            this.keyValues.put("Acquisition_date", this.acquisitionDate);

           /* if(matcher.group("series") != null)
                ;*/
        } else {
            IJLogger.error("The name "+imgName+ "is not correctly formatted. Please format it like : \\n lsm980_o63x_z1.2_oil_ArgoSLG511_b_d20230223_1.extension "+
                    "\\n sp8up1_063x_z2.6_gly_ArgoSLG511_b_d20230223.lif [img1] for lif files");
        }
    }

    /**
     * remove the extension from an image name
     *
     * @param name
     * @return
     */
    private static String getNameWithoutExtension(String name){
        // special case for lif files
        if(name.contains(".lif"))
            return name.replace(".lif","");
        // special case for vsi files
        if(name.contains(".vsi"))
            return name.replace(".vsi","");

        int pos = name.lastIndexOf(".");
        if (pos > 0) {
            name = name.substring(0, pos);
        }

        return name;
    }

    /**
     *
     * @return
     */
    public Map<List<String>, List<List<Double>>> summaryForParentTable(){
        List<List<Double>> summaryChannelsList = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        Map<String, Double> statMap;

        for(ImageChannel channel:this.channels){
            statMap = channel.channelSummary();
            statMap.put("Acquisition_date", this.acquisitionDate == null || this.acquisitionDate.equals("") ? 0.0 : Double.parseDouble(this.acquisitionDate));
            summaryChannelsList.add(new ArrayList<>(statMap.values()));
            headers = new ArrayList<>(statMap.keySet());
        }

        Map<List<String>, List<List<Double>>> finalMap = new HashMap<>();
        finalMap.put(headers,summaryChannelsList);

        return finalMap;
    }

    public void computePCC(){
        // compute PCC if there is more than 1 channel
        if(this.channels.size() > 1){

            List<Roi> rois = this.channels.get(0).getGridRings();
            for(int i = 0; i < this.channels.size()-1; i++){
                for(int j = i+1; j < this.channels.size(); j++){
                    // get the two images
                    this.image.setPosition(i+1,1,1);
                    ImagePlus ch1 = new ImagePlus("ch"+i, this.image.getProcessor());
                    this.image.setPosition(j+1,1,1);
                    ImagePlus ch2 = new ImagePlus("ch"+j, this.image.getProcessor());

                    // compute Pearson Correlation Coefficient
                    pccValues.add(Tools.computePCC(ch1, ch2, rois));
                }
            }
        } else IJLogger.warn("PCC computation", "Only one channel for image "+this.imgName +". Cannot compute PCC.");
    }

}
