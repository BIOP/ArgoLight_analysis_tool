package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Processing {

    public static void run(OMERORetriever retriever, boolean savingHeatMaps, Sender sender){
        Map<ImageWrapper, List<List<Double>>> summaryMap = new HashMap<>();
        List<String> headers = new ArrayList<>();

        for (int i = 0; i < retriever.getNImages(); i++) {
           // try {
                // get the image
                ImagePlus imp = retriever.getImage(i);
                // get the imageWrapper
                ImageWrapper imageWrapper = retriever.getImageWrapper(i);
                if (imp == null || imageWrapper == null)
                    continue;
                // create a new ImageFile object
                ImageFile imageFile = new ImageFile(imp, imageWrapper.getId());
                boolean isSGL482 = false;

                // choose the right ArgoLight processing
                if (imageFile.getArgoSlideName().contains("SGL482")) {
                    ArgoSGL482Processing.run(imageFile);
                    isSGL482 = true;
                } else {
                    ArgoSGL511Processing.run(imageFile);
                }

                // send image results (metrics, rings, tags, key-values)
                sender.initialize(imageFile, imageWrapper);
                sender.sendTags(imageFile.getTags(), imageWrapper, retriever.getClient());
                sendResults(sender, imageFile, savingHeatMaps, isSGL482);

                // metrics summary to populate parent table
                Map<List<String>, List<List<Double>>> allChannelMetrics = imageFile.summaryForParentTable();
                headers = new ArrayList<>(allChannelMetrics.keySet()).get(0);
                if (!allChannelMetrics.values().isEmpty())
                    summaryMap.put(imageWrapper, allChannelMetrics.values().iterator().next());
            /*}catch (Exception e){
                IJLogger.error("An error occured during processing ; cannot analyse the image "+retriever.getImage(i).getTitle());
            }*/
        }

        // populate parent table with summary results
        sender.populateParentTable(summaryMap, headers, !retriever.isProcessingAllRawImages());
    }

    private static void sendResults(Sender sender, ImageFile imageFile, boolean savingHeatMaps, boolean is482SGL){
        Map<String, String> keyValues = imageFile.getKeyValues();

        // send PCC table
        if (imageFile.getNChannels() > 1)
            sender.sendPCCTable(imageFile.getPCC(), imageFile.getNChannels());

        List<List<Double>> distortionValues = new ArrayList<>();
        List<List<Double>> uniformityValues = new ArrayList<>();
        List<List<Double>> fwhmValues = new ArrayList<>();
        List<Integer> chIds = new ArrayList<>();

        for (int i = 0; i < imageFile.getNChannels(); i++) {
            ImageChannel channel = imageFile.getChannel(i);
            // get channel keyValues
            keyValues.putAll(channel.getKeyValues());
            // send Rois
            sender.sendGridPoints(channel.getGridRings(), channel.getId(), "measuredGrid");
            sender.sendGridPoints(channel.getIdealGridRings(), channel.getId(), "idealGrid");

            // collect metrics for each channel
            distortionValues.add(channel.getFieldDistortion());
            uniformityValues.add(channel.getFieldUniformity());
            fwhmValues.add(channel.getFWHM());
            chIds.add(channel.getId());

            // send heat maps
            if (savingHeatMaps) {
                if(is482SGL && imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFieldDistortionHeatMap(imageFile.getImgNameWithoutExtension()));
                if(is482SGL && imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFieldUniformityHeatMap(imageFile.getImgNameWithoutExtension()));
                if(is482SGL && !imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFWHMHeatMap(imageFile.getImgNameWithoutExtension()));
            }
        }

        // send results table
        if(is482SGL && imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(distortionValues, chIds, false, "Field_distortion");
        if(is482SGL && imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(uniformityValues, chIds, false, "Field_uniformity");
        if(is482SGL && !imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(fwhmValues, chIds, false, "FWHM");

        // send key values
        if(sender instanceof LocalSender)
            keyValues.put("Image_ID",""+imageFile.getId());
        sender. sendKeyValues(keyValues);
    }
}
