package ch.epfl.biop.senders;

import ch.epfl.biop.ImageChannel;
import ch.epfl.biop.ImageFile;
import ch.epfl.biop.Retriever;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;
import ij.gui.Roi;

import java.awt.geom.Point2D;
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
}
