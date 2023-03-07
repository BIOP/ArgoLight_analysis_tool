package ch.epfl.biop.senders;

import ch.epfl.biop.IJLogger;
import ch.epfl.biop.ImageChannel;
import ch.epfl.biop.ImageFile;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.RoiEncoder;
import mdbtools.libmdb.file;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LocalSender implements Sender{

    private String parentFolder;

    public LocalSender(String target){
        this.parentFolder = target;
    }
    @Override
    public void sendResults(ImageFile imageFile, ImageWrapper imageWrapper, boolean savingHeatMaps) {
        sendKeyValues(imageFile.getKeyValues());
        if(imageFile.getNChannels() > 1)
            sendPCCTable(imageFile.getPCC(), imageFile.getNChannels());

        for(int i = 0; i < imageFile.getNChannels(); i++){
            ImageChannel channel = imageFile.getChannel(i);
            sendGridPoints(channel.getGridRings(), channel.getId(), "measuredGrid"); // working
            sendGridPoints(channel.getIdealGridRings(), channel.getId(),"idealGrid"); // working
            // sendKeyValues(channel.getKeyValues()); //working
            sendResultsTable(channel.getFieldDistortion(), channel.getFieldUniformity(), channel.getFWHM(), channel.getId());

            if(savingHeatMaps) {
                sendHeatMaps(channel.getFieldDistortionHeatMap(imageFile.getImgNameWithoutExtension()), this.parentFolder);
                sendHeatMaps(channel.getFieldUniformityHeatMap(imageFile.getImgNameWithoutExtension()), this.parentFolder);
                sendHeatMaps(channel.getFWHMHeatMap(imageFile.getImgNameWithoutExtension()), this.parentFolder);
            }
        }
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

        File file = new File(this.parentFolder + File.separator + "keyValues.csv");
        saveCsvFile(file, text);
    }

    @Override
    public void sendGridPoints(List<Roi> rois,  int channelId, String roiTitle) {
        String path = this.parentFolder + File.separator + roiTitle + "_ch" + channelId + ".zip";
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

            File file = new File(this.parentFolder + File.separator + "Results_table_ch"+channelId+".csv");
            saveCsvFile(file, text);
        }
    }

    @Override
    public void populateParentTable(Map<ImageWrapper, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable) {

    }

    @Override
    public void sendPCCTable(List<List<Double>> pccValues, int nChannels) {

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
}
