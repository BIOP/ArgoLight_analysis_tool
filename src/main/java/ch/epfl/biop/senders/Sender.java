package ch.epfl.biop.senders;

import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.retrievers.Retriever;
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
     * @param retriever Interface handling the raw images
     */
    void initialize(ImageFile imageFile, Retriever retriever);

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
     * @param values measured features for all channels
     * @param channelIdList list of channels
     * @param createNewTable true if you want to create a new file
     * @param tableName file name
     */
    void sendResultsTable(List<List<Double>> values, List<Integer> channelIdList, boolean createNewTable, String tableName);

    /**
     * Save summary table and new entries if new images are acquired
     * @param retriever Interface handling the raw images
     * @param summary summary metrics for each image
     * @param headers metrics names and other headers
     * @param populateExistingTable true if you want to add the current summary to the current table ;
     *                              false if you want to create a new file.
     */
    void populateParentTable(Retriever retriever, Map<String, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable);

    /**
     * Save Pearson Correlation Coefficient analysis table
     * @param pccValues
     * @param nChannels
     */
    void sendPCCTable(List<List<Double>> pccValues, int nChannels);

    /**
     * Send to OMERO all the tags created for the current image
     * @param tags
     */
    void sendTags(List<String> tags);

    /**
     * Delete all previous runs (ROIs, tables, key-value pairs...) except tags and heatmaps, both located on OMERO
     */
    void clean();
}
