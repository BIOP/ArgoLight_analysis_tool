package ch.epfl.biop.retrievers;

import ij.ImagePlus;
import java.util.List;

/**
 * Implements basic functionalities of an image retriever.
 */
public interface Retriever {
    /**
     *
     * @param parentTarget parent container with all the microscopes
     * @param microscopeName name of the microscope to process
     * @param processAllImages true if you want to process all available images, regardless if they have already been processed
     * @param argoSlideName The name of the selected ArgoSlide
     */
    boolean loadImages(String parentTarget, String microscopeName, boolean processAllImages, String argoSlideName);

    /**
     * @param index image position in the list
     * @return a list of {@link ImagePlus} objects read from the image file (i.e. list of image series)
     */
    List<ImagePlus> getImage(String index);

    /**
     * @return the number of an images to process.
     */
    int getNImages();

    /**
     * @return the list of image IDs
     */
    List<String> getIDs();

    /**
     * @return the container id (local folder path or OMERO dataset id) that is used to save processing results
     */
    String getMicroscopeTarget();

    /**
     * @return true if you want to process all available images, regardless if they have already been processed
     */
    boolean isProcessingAllRawImages();
}
