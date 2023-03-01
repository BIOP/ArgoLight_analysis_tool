package ch.epfl.biop.senders;

import ch.epfl.biop.ImageChannel;
import ch.epfl.biop.ImageFile;
import ch.epfl.biop.Retriever;
import ij.ImagePlus;
import ij.gui.Roi;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

public interface Sender {

    void sendResults(ImageFile imageFile, Retriever retriever, boolean savingHeatMaps);
    void sendHeatMaps(ImageChannel channel, String imageName, String target);
    void sendKeyValues(Map<String, String> keyValues);
    void sendGridPoints(List<Roi> grid, List<Roi> ideal, int channelId);
    void sendResultsTable(List<Double> fieldDistortion, List<Double> fieldUniformity, List<Double> fwhm, int channelId);
    void populateParentTable();
}
