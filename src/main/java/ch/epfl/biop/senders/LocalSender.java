package ch.epfl.biop.senders;

import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.RoiEncoder;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LocalSender implements Sender{
    private final String parentFolder;
    private String imageFolder;

    public LocalSender(File target, String microscopeName){
        //Check if the selected folder is the microscope folder
        if(!target.getName().contains(microscopeName)){
            // list files in the folder
            File[] files = target.listFiles();
            if(files != null) {
                // find the one with the microscope name
                List<File> microscopeList = Arrays.stream(files)
                        .filter(e -> e.isDirectory() && e.getName().contains(microscopeName))
                        .collect(Collectors.toList());

                if (microscopeList.isEmpty()){
                    // create the microscope folder if it doesn't exist
                    String microscopeFolderPath = target.getAbsolutePath() + File.separator + microscopeName;
                    if(new File(microscopeFolderPath).mkdir())
                        this.parentFolder = microscopeFolderPath;
                    else{
                        IJLogger.error("Cannot create folder "+microscopeFolderPath+ ". Use this folder instead "+target.getAbsolutePath());
                        this.parentFolder = target.getAbsolutePath();
                    }
                }else{
                    // select the existing microscope folder
                    File microscopeFolder = microscopeList.get(0);
                    this.parentFolder = microscopeFolder.getAbsolutePath();
                }
            }else{
                // create the microscope folder if it doesn't exist
                String microscopeFolderPath = target.getAbsolutePath() + File.separator + microscopeName;
                if(new File(microscopeFolderPath).mkdir())
                    this.parentFolder = microscopeFolderPath;
                else{
                    IJLogger.error("Cannot create folder "+microscopeFolderPath+ ". Use this folder instead "+target.getAbsolutePath());
                    this.parentFolder = target.getAbsolutePath();
                }
            }
        }else this.parentFolder = target.getAbsolutePath();
    }

    @Override
    public void sendResults(ImageFile imageFile, ImageWrapper imageWrapper, boolean savingHeatMaps) {
        // create the image folder
        this.imageFolder = this.parentFolder + File.separator + imageFile.getImgNameWithoutExtension();
        File imageFileFolder = new File(this.imageFolder);

        if(imageFileFolder.exists() || imageFileFolder.mkdir()) {
            // get image keyValues
            Map<String, String> keyValues = imageFile.getKeyValues();

            // send PCC table
            if (imageFile.getNChannels() > 1)
                sendPCCTable(imageFile.getPCC(), imageFile.getNChannels());

            for (int i = 0; i < imageFile.getNChannels(); i++) {
                ImageChannel channel = imageFile.getChannel(i);
                // get channel keyValues
                keyValues.putAll(channel.getKeyValues());
                // send Rois
                sendGridPoints(channel.getGridRings(), channel.getId(), "measuredGrid");
                sendGridPoints(channel.getIdealGridRings(), channel.getId(), "idealGrid");
                // send Results table
                sendResultsTable(channel.getFieldDistortion(), channel.getFieldUniformity(), channel.getFWHM(), channel.getId());

                // send heat maps
                if (savingHeatMaps) {
                    sendHeatMaps(channel.getFieldDistortionHeatMap(imageFile.getImgNameWithoutExtension()), this.imageFolder);
                    sendHeatMaps(channel.getFieldUniformityHeatMap(imageFile.getImgNameWithoutExtension()), this.imageFolder);
                    sendHeatMaps(channel.getFWHMHeatMap(imageFile.getImgNameWithoutExtension()), this.imageFolder);
                }
            }

            // send key values
            keyValues.put("Image_ID",""+imageFile.getId());
            sendKeyValues(keyValues);

        } else IJLogger.error("Cannot create image folder "+this.imageFolder);
    }

    @Override
    public void sendHeatMaps(ImagePlus imp, String target) {
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(target,imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

         //check if the image was correctly saved
        if(hasBeenSaved) IJLogger.info("Saving heatmaps locally"+imp.getTitle()+".tif"+" was saved in : "+ target);
        else IJLogger.error("Saving heatmaps locally","Cannot save "+imp.getTitle()+ " in "+target);
    }

    @Override
    public void sendKeyValues(Map<String, String> keyValues) {
        String text = "key,value\n";
        for(Map.Entry<String, String> keyValue : keyValues.entrySet()){
            text += keyValue.getKey() + "," + keyValue.getValue()+"\n";
        }

        File file = new File(this.imageFolder + File.separator + "keyValues.csv");
        saveCsvFile(file, text);
    }

    @Override
    public void sendGridPoints(List<Roi> rois,  int channelId, String roiTitle) {
        String path = this.imageFolder + File.separator + roiTitle + "_ch" + channelId + ".zip";
        DataOutputStream out  = null;
        try {
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
            out = new DataOutputStream(new BufferedOutputStream(zos));
            RoiEncoder re = new RoiEncoder(out);
            for (int i = 0; i < rois.size(); i++) {
                String label = roiTitle + ":" + i + ":child";
                Roi roi = rois.get(i);
                IJLogger.info("Saving ROIs","saveMultiple: "+i+"  "+label+"  "+roi);
                if (roi == null) continue;
                if (!label.endsWith(".roi")) label += ".roi";
                zos.putNextEntry(new ZipEntry(label));
                re.write(roi);
                out.flush();
            }
            out.close();
        } catch (IOException e) {
            IJLogger.error("Saving ROIs", "An error occurs during saving process");
        } finally {
            if (out!=null)
                try {out.close();} catch (IOException e) {}
        }
    }

    @Override
    public void sendResultsTable(List<Double> fieldDistortion, List<Double> fieldUniformity, List<Double> fwhm, int channelId) {
        if(fieldDistortion.size() == fieldUniformity.size() && fieldUniformity.size() == fwhm.size()) {
            String text = "field_distortion,field_uniformity,FWHM\n";
            for (int i = 0; i < fieldDistortion.size(); i++) {
                text += fieldDistortion.get(i) + "," + fieldUniformity.get(i) + "," + fwhm.get(i) + "\n";
            }

            File file = new File(this.imageFolder + File.separator + "Results_table_ch"+channelId+".csv");
            saveCsvFile(file, text);
        }
    }

    @Override
    public void populateParentTable(Map<ImageWrapper, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable) {


    }

    @Override
    public void sendPCCTable(List<List<Double>> pccValues, int nChannels) {
        List<Integer> chs1 = new ArrayList<>();
        List<Integer> chs2 = new ArrayList<>();

        for(int i = 0; i < nChannels-1; i++)
            for(int j = i+1; j < nChannels; j++){
                chs1.add(i);
                chs2.add(j);
            }
        String text = "";
        for (int i = 0; i < chs1.size(); i++) {
            text += "ch"+chs1.get(i)+"_ch"+chs2.get(i)+",";
        }
        text += "\n";

        for (List<Double> channelPCCValue : pccValues) {
            for (Double ringPCCValue : channelPCCValue) text += ringPCCValue + ",";
            text += "\n";
        }

        File file = new File(this.imageFolder + File.separator + "PCC_table.csv");
        saveCsvFile(file, text);
    }

    private void saveCsvFile(File file, String text){
        try {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
                bw.write(text);
            }
        }catch (IOException e){
            IJLogger.error("Saving KeyValues", "Error when saving key values as csv in "+file.getAbsolutePath());
        }
    }

    /**
     * get the last table in the specified folder that correspond to the current microscope
     *
     * @param folder
     * @param testedMicroscope
     * @return
     */
    public static File getLastLocalTable(File folder, String testedMicroscope){
        // list all files within the folder
        File[] childfiles = folder.listFiles();

        if(childfiles == null)
            return null;

        // get all names of csv files
        List<String> names = Arrays.stream(childfiles)
                .filter(e -> e.isFile() && e.getName().contains("."))
                .filter(f -> f.getName().substring(f.getName().lastIndexOf(".") + 1).equals("csv"))
                .map(File::getName)
                .collect(Collectors.toList());

        if(names.isEmpty())
            return null;

        // filter only argoLight related csv files
        names = names.stream().filter(e->e.contains(testedMicroscope)).collect(Collectors.toList());

        // get dates
        List<String> orderedDate = new ArrayList<>();
        names.forEach(name-> orderedDate.add(name.split("_")[0]));

        // sort dates in reverse order (larger to smaller date)
        orderedDate.sort(Comparator.reverseOrder());

        if(orderedDate.isEmpty())
            return null;

        // get the name of the latest csv file
        String lastTable = names.stream().filter(e -> e.contains(orderedDate.get(0))).collect(Collectors.toList()).get(0);

        // return the csv file
        return new File(folder + File.separator + lastTable);
    }


}
