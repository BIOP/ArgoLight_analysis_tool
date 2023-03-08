package ch.epfl.biop.senders;

import ch.epfl.biop.image.ImageFile;
import fr.igred.omero.Client;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;
import ij.gui.Roi;

import java.util.List;
import java.util.Map;

public interface Sender {

    void sendResults(ImageFile imageFile, ImageWrapper imageWrapper, boolean savingHeatMaps);
    void sendHeatMaps(ImagePlus imp, String target);
    void sendKeyValues(Map<String, String> keyValues);
    void sendGridPoints(List<Roi> rois, int channelId, String roiTitle);
    void sendResultsTable(List<Double> fieldDistortion, List<Double> fieldUniformity, List<Double> fwhm, int channelId);
    void populateParentTable(Map<ImageWrapper, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable);
    void sendPCCTable(List<List<Double>> pccValues, int nChannels);
    void sendTags(List<String> tags, ImageWrapper imageWrapper, Client client);
}
