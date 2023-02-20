package ch.epfl.biop;

import fr.igred.omero.repository.ImageWrapper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageFile {
    Pattern pattern = Pattern.compile("(?<microscope>.*)_o(?<objective>.*)_z(?<zoom>.*)_(?<immersion>.*)_(?<argoslide>.*)_(?<pattern>.*)_d(?<date>[\\d]*)_?(?<series>.*)?\\.(?<extension>.*)");
    private String imgName;
    private String microscope;
    private String objective;
    private String immersionMedium;
    private String argoSlideName;
    private String acquisitionDate;
    private int zoomFactor;
    private String argoSlidePattern;

    private ImageWrapper imageWrapper;
    private List<Double> pccValues;

    public List<ImageChannel> channels;

    public ImageFile(ImageWrapper imageWrapper){
        this.imageWrapper = imageWrapper;
        this.imgName = imageWrapper.getName();





    }

    private void parseImageName(String imgName){
        Matcher matcher = pattern.matcher(imgName);

        if(matcher.find()) {
            this.microscope = matcher.group("microscope");
            if(matcher.group("series") != null)
                ;
        } else {
            // si oattern ne match pas
        }
        String[] tokens = imgName.split("_");

        if(!((imgName.contains(".lif") && tokens.length == 7) || tokens.length == 8)) {
        IJLogger.error("The name "+imgName+ "is not correctly formatted. Please format it like : microsocpe_obj_zoom_immersion_argoslide-name_pattern_acqDate_id* without id for .lif files");
            return;
        }


    }

}
