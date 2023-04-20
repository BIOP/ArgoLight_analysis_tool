package ch.epfl.biop.senders;

import ch.epfl.biop.image.ImageFile;
import fr.igred.omero.Client;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;
import ij.gui.Roi;

import java.util.List;
import java.util.Map;

/**
 * Implements basic functionalities of a metadata sender.
 */
public interface Sender {

    /**
     * Initialize the sender
     * @param imageFile Object containing information about the image
     * @param imageWrapper Remote object {@link ImageWrapper} to handle OMERO image
     */
    void initialize(ImageFile imageFile, ImageWrapper imageWrapper);

    /**
     * Save heat maps of computed features
     * @param imp the heat map
     */
    void sendHeatMaps(ImagePlus imp);

    /**
     * Save metadata as key-value pairs
     * @param keyValues
     */
    void sendKeyValues(Map<String, String> keyValues);

    /**
     * Save the list of grid point positions as ROI object
     * @param rois Points to save
     * @param channelId channel of interest
     * @param roiTitle Description of what are these rois
     */
    void sendGridPoints(List<Roi> rois, int channelId, String roiTitle);

    /**
     * Save the table regrouping measured features on the imaged grid
     * @param values measured features
     * @param channelIdList
     * @param replaceExistingTable
     * @param tableName
     */
    void sendResultsTable(List<List<Double>> values, List<Integer> channelIdList, boolean replaceExistingTable, String tableName);
    void populateParentTable(Map<ImageWrapper, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable);
    void sendPCCTable(List<List<Double>> pccValues, int nChannels);
    void sendTags(List<String> tags, ImageWrapper imageWrapper, Client client);
}
