package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.senders.Sender;
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

        for(int i = 0; i < retriever.getNImages(); i++) {
            // get the image
            ImagePlus imp = retriever.getImage(i);
            // get the imageWrapper
            ImageWrapper imageWrapper = retriever.getImageWrapper(i);
            // create a new ImageFile object
            ImageFile imageFile = new ImageFile(imp, imageWrapper.getId());

            // choose the right ArgoLight processing
            if(imageFile.getArgoSlideName().contains("SLG482")){
                ArgoSLG482Processing.run(imageFile);
            }else{
                ArgoSLG511Processing.run(imageFile);
            }

            // send image results (metrics, rings, tags, key-values)
            sender.sendTags(imageFile.getTags(), imageWrapper, retriever.getClient());
            sender.sendResults(imageFile, imageWrapper, savingHeatMaps);

            // metrics summary to populate parent table
            Map<List<String>, List<List<Double>>> allChannelMetrics = imageFile.summaryForParentTable();
            headers = new ArrayList<>(allChannelMetrics.keySet()).get(0);
            if(!allChannelMetrics.values().isEmpty())
                summaryMap.put(imageWrapper, allChannelMetrics.values().iterator().next());
        }
        // populate parent table with summary results
        sender.populateParentTable(summaryMap, headers, !retriever.isProcessingAllRawImages());
    }
}
