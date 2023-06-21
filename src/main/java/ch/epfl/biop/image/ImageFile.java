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

/**
 * Object containing all information and methods related to the image file.
 * The image can be made of multiple channels
 */
public class ImageFile {

    /**
     * regex pattern for image name, depending on the file format
     */
    public enum FILETYPE{
        SINGLE("Single file",
                Pattern.compile("(?<microscope>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_o(?<objective>.*?)_(?<immersion>.*?)_(?<fov>.*)_(?<serie>.*)\\.(?<extension>.*)"),
                Pattern.compile(".*[\\.][a-zA-Z]*")),

        MULTIPLE_OMERO("File coming from a fileset",
                 Pattern.compile("(?<microscope>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_o(?<objective>.*?)_(?<immersion>.*?)\\.(?<extension>[\\w]*).*\\[(?<fov>.*)_(?<serie>.*)\\]"),
                Pattern.compile(".*[\\.].*\\[.*\\]")),

        MULTIPLE_LOCAL("File coming from a fileset",
                Pattern.compile("(?<microscope>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_o(?<objective>.*?)_(?<immersion>.*?)\\.(?<extension>[\\w]*).*\\- (?<fov>.*)_(?<serie>.*)"),
                Pattern.compile(".*[\\.].*\\-.*")),

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

    final private String imgNameWithoutExtension;
    final private ImagePlus image;
    final private long id;
    final private int serie;
    private String microscope;
    private String objective;
    private String immersionMedium;
    private String argoSlideName;
    private String acquisitionDate;
    private String imagedFoV;
    private String argoSlidePattern;
    private List<String> tags = new ArrayList<>();
    private Map<String, String> keyValues = new TreeMap<>();
    private List<List<Double>> pccValues = new ArrayList<>();
    public List<ImageChannel> channels = new ArrayList<>();

    public ImageFile(ImagePlus imp, long id, int serie){
        this.image = imp;
        this.id = id;
        this.serie = serie;
        String imgName = imp.getTitle();
        this.imgNameWithoutExtension = getNameWithoutExtension(imgName);
        parseImageName(imgName);
    }

    /**
     * add an {@link ImageChannel} object
     * @param imageChannel
     */
    public void addChannel(ImageChannel imageChannel){
        channels.add(imageChannel);
    }

    /**
     * add new tags
     * @param tags
     */
    public void addTags(String... tags){
        this.tags.addAll(Arrays.asList(tags));
    }

    /**
     * add new key-value pair
     * @param key
     * @param value
     */
    public void addKeyValue(String key, String value){
        this.keyValues.put(key, value);
    }

    /**
     * @return the number of channels for the current image
     */
    public int getNChannels(){ return this.channels.size(); }

    /**
     * @return the image name without file extension
     */
    public String getImgNameWithoutExtension() { return this.imgNameWithoutExtension; }

    /**
     * @return all key-values for the current image
     */
    public Map<String,String> getKeyValues(){ return this.keyValues; }

    /**
     * @return the image id
     */
    public long getId(){ return this.id; }

    /**
     * @return the image serie
     */
    public long getSerie(){ return this.serie; }

    /**
     * @return the ImagePlus object of the current image
     */
    public ImagePlus getImage(){ return this.image; }

    /**
     * @return fullFoV or partialFoV
     */
    public String getImagedFoV(){ return this.imagedFoV; }

    /**
     * @return tags attached to the current image
     */
    public List<String> getTags(){ return this.tags; }

    /**
     * @return the PCC values for all the channels
     */
    public List<List<Double>> getPCC(){ return this.pccValues; }

    /**
     * @return the name of slide based on the image name
     */
    public String getArgoSlideName(){ return this.argoSlideName; }

    /**
     * @param id channel id
     * @return the ImageChannel object corresponding the channel id
     */
    public ImageChannel getChannel(int id){
        if(id >= this.channels.size()) {
            IJLogger.error("Get image channel", "You try to access to channel "+id+ " that doesn't exists");
            return null;
        }
        return this.channels.get(id);
    }

    /**
     * Use the regex pattern to match tokens in the image name and extract metadata. The image name must follow
     * strict patterns
     * <p>
     * <ul>
     * <li> MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion_FoV_serie.extension for single file
     * (ex: lsm980_ArgoSLG482_b_d20230405_o63x_oil_fullFoV_1.czi) </li>
     * <li> MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion.extension [FoV_serie] for fileset images
     * (ex: sp8up1_ArgoSLG482_b_d20230405_o63x_oil.lif [fullFoV_1] </li>
     * </ul>
     * <p>
     * @param imgName
     */
    private void parseImageName(String imgName){

        Optional<FILETYPE> filePattern = Arrays.stream(FILETYPE.values()).filter(ft -> ft.matchesType(imgName)).findFirst();

        if(!filePattern.isPresent()) {
            IJLogger.error("The name "+imgName+ "is not correctly formatted. Please format it like : "+
                    "\\n MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion_FoV_serie.extension for single file (ex: lsm980_ArgoSLG482_b_d20230405_o63x_oil_fullFoV_1.czi)"+
                    "\\n MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion.extension [FoV_serie] for fileset images (ex: sp8up1_ArgoSLG482_b_d20230405_o63x_oil.lif [fullFoV_1]");
            return;
        }

        Matcher matcher = filePattern.get().pattern.matcher(imgName);

        if(matcher.find()) {
            //populate variables
            this.microscope = matcher.group("microscope");
            this.objective = matcher.group("objective");
            this.imagedFoV = matcher.group("fov");
            this.immersionMedium = matcher.group("immersion");
            this.argoSlideName = matcher.group("argoslide");
            this.argoSlidePattern = matcher.group("pattern");
            this.acquisitionDate = matcher.group("date");

            // add them as tags
            addTags(this.imagedFoV, this.objective, this.argoSlideName,
                    this.microscope.toLowerCase(), this.immersionMedium, this.argoSlidePattern);

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
     * Make a summary map for each metrics (key) and for all channels
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

    /**
     * compute the Pearson Correlation Coefficient (PCC) between the channels of the current image
     */
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
        } else IJLogger.warn("PCC computation", "Only one channel for image "+this.image.getTitle() +". Cannot compute PCC.");
    }
}
