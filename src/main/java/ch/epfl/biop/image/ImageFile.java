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
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageFile {

    public enum FILETYPE{
        SINGLE("Single file",
                Pattern.compile("(?<microscope>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_o(?<objective>.*?)_(?<immersion>.*?)_(?<fov>.*)_(?<serie>.*)\\.(?<extension>.*)"),
                Pattern.compile(".*[\\.][a-zA-Z]*")),

        MULTIPLE("File coming from a fileset",
                 Pattern.compile("(?<microscope>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_o(?<objective>.*?)_(?<immersion>.*?)\\.(?<extension>[\\w]*).*\\[(?<fov>.*)_(?<serie>.*)\\]"),
                Pattern.compile(".*[\\.].*\\[.*\\]")),

        //TODO see if there is conflicted with others
        OLDPROTOCOL("Single or multiple files with ArgoSim first protocol naming convention",
                Pattern.compile("(?<microscope>.*)_o(?<objective>.*)_(?<immersion>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_?(?<series>.*)?\\.(?<extension>.*)"),
                Pattern.compile(".*"));

        private final String type;
        private final Pattern pattern;
        private final Pattern typeMatching;

        FILETYPE(String type, Pattern pattern, Pattern comparator) {
            this.type = type;
            this.pattern = pattern;
            this.typeMatching = comparator;
        }

        boolean matchesType(String s) { return typeMatching.matcher(s).matches(); }

    }

    private final String imgName;
    private final String imgNameWithoutExtension;
    private String microscope;
    private String objective;
    private String immersionMedium;
    private String argoSlideName;
    private String acquisitionDate;
    private String imagedFoV;
    private String argoSlidePattern;
    private final ImagePlus image;
    private List<String> tags = new ArrayList<>();
    private Map<String, String> keyValues = new TreeMap<>();
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
    public String getImagedFoV(){ return this.imagedFoV; }
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

        Optional<FILETYPE> filePattern = Arrays.stream(FILETYPE.values()).filter(ft -> ft.matchesType(imgName)).findFirst();

        if(!filePattern.isPresent()) {
            IJLogger.error("The name "+imgName+ "is not correctly formatted. Please format it like : "+
                    "\\n MicrosocpeName_ArgoSlideName_pattern_dDate_oObjective_immersion_FoV_serie.extension for single file (ex: lsm980_ArgoSLG482_b_d20230405_o63x_oil_fullFoV_1.czi)"+
                    "\\n MicrosocpeName_ArgoSlideName_pattern_dDate_oObjective_immersion.extension [FoV_serie] for fileset images (ex: sp8up1_ArgoSLG482_b_d20230405_o63x_oil.lif [fullFoV_1]");
            return;
        }

        Matcher matcher = filePattern.get().pattern.matcher(imgName);

        if(matcher.find()) {
            this.microscope = matcher.group("microscope");
            this.objective = matcher.group("objective");
            this.imagedFoV = matcher.group("fov");
            this.immersionMedium = matcher.group("immersion");
            this.argoSlideName = matcher.group("argoslide");
            this.argoSlidePattern = matcher.group("pattern");
            this.acquisitionDate = matcher.group("date");

            this.keyValues.put("Microscope", this.microscope);
            this.keyValues.put("Objective", this.objective);
            this.keyValues.put("Immersion", this.immersionMedium);
            this.keyValues.put("FOV", this.imagedFoV);
            this.keyValues.put("ArgoSlide_name", this.argoSlideName);
            this.keyValues.put("ArgoSlide_pattern", this.argoSlidePattern);
            this.keyValues.put("Acquisition_date", this.acquisitionDate);

           /* if(matcher.group("series") != null)
                ;*/
        } else {
            IJLogger.error("The name "+imgName+ "is not correctly formatted. Please format it like : "+
                    "\\n MicrosocpeName_ArgoSlideName_pattern_dDate_oObjective_immersion_FoV_serie.extension for single file (ex: lsm980_ArgoSLG482_b_d20230405_o63x_oil_fullFoV_1.czi)"+
                    "\\n MicrosocpeName_ArgoSlideName_pattern_dDate_oObjective_immersion.extension [FoV_serie] for fileset images (ex: sp8up1_ArgoSLG482_b_d20230405_o63x_oil.lif [fullFoV_1]");
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
