package ch.epfl.biop;

import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageFile {
    private final Pattern pattern = Pattern.compile("(?<microscope>.*)_o(?<objective>.*)_z(?<zoom>.*)_(?<immersion>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_?(?<series>.*)?\\.(?<extension>.*)");
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
    private final int id;
    private List<Double> pccValues;
    public List<ImageChannel> channels = new ArrayList<>();

    public ImageFile(ImagePlus imp, int id){
        this.image = imp;
        this.id = id;
        this.imgName = imp.getTitle();//.getOriginalFileInfo().fileName;
        this.imgNameWithoutExtension = getNameWithoutExtension(this.imgName);
        parseImageName(this.imgName);
    }

    public void addChannel(ImageChannel imageChannel){
        channels.add(imageChannel);
    }

    public List<ImageChannel> getChannels(){
        return this.channels;
    }
    public String getImgNameWithoutExtension() { return this.imgNameWithoutExtension; }

    public int getId(){
        return this.id;
    }

    public ImageChannel getChannel(int id){
        if(id >= this.channels.size()) {
            IJLogger.error("Get image channel", "You try to access to channel "+id+ " that doesn't exists");
            return null;
        }
        return this.channels.get(id);
    }

    public Map<String, String> getImageNameParsing(){
        Map<String, String> commonKeyValues = new TreeMap<>();

        commonKeyValues.put("Microscope", this.microscope);
        commonKeyValues.put("Objective", this.objective);
        commonKeyValues.put("Immersion", this.immersionMedium);
        commonKeyValues.put("Zoom", this.zoomFactor);
        commonKeyValues.put("ArgoSlide_name", this.argoSlideName);
        commonKeyValues.put("ArgoSlide_pattern", this.argoSlidePattern);
        commonKeyValues.put("Acquisition_date", this.acquisitionDate);

        return commonKeyValues;
    }

    private void parseImageName(String imgName){
        Matcher matcher = pattern.matcher(imgName);

        if(matcher.find()) {
            this.microscope = matcher.group("microscope");
            this.objective = matcher.group("objective");
            this.zoomFactor = matcher.group("zoom");
            this.immersionMedium = matcher.group("immersion");
            this.argoSlideName = matcher.group("argoslide");
            this.argoSlidePattern = matcher.group("pattern");
            this.acquisitionDate = matcher.group("date");

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

}
