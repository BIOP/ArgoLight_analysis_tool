package ch.epfl.biop.retrievers;

import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.utils.Tools;
import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Class that retrieve images from OMERO database, based on a container ID.
 */
public class OMERORetriever implements Retriever {
    final private Client client;
    private Map<String,ImageWrapper> images = new HashMap<>();
    private long datasetId = -1;
    private boolean processAllRawImages = false;

    public OMERORetriever(Client client){
        this.client = client;
    }

    /**
     * Retrieve from OMERO images that need to be processed.
     *
     * @param parentTarget where to look for images
     * @param microscopeName name of the dataset where to look for images (corresponding to the microscope name)
     * @param processAllRawImages true if you want to process all images within the dataset, regardless if
     *                            they have already been processed one.
     * @param argoSlideName Name of the selected ArgoSlide
     * @return builder
     */
    @Override
    public boolean loadImages(String parentTarget, String microscopeName, boolean processAllRawImages, String argoSlideName) {
        this.processAllRawImages = processAllRawImages;
        long projectId;
        try {
            projectId = Long.parseLong(parentTarget);
        } catch (Exception e){
            IJLogger.error("Load OMERO images", "The project ID '"+parentTarget+"' is not an integer number");
            return false;
        }

        try {
            // get the ArgoSim project
            ProjectWrapper project_wpr = this.client.getProject(projectId);

            // get the specified dataset
            List<DatasetWrapper> datasetWrapperList = project_wpr.getDatasets().stream().filter(e -> e.getName().toLowerCase().contains(microscopeName)).collect(Collectors.toList());

            if (datasetWrapperList.size() == 1) {
                DatasetWrapper datasetWrapper = datasetWrapperList.get(0);
                IJLogger.info("Load OMERO images","Images downloaded from dataset : " + datasetWrapper.getName());
                this.datasetId = datasetWrapper.getId();

                List<ImageWrapper> imageWrapperList = datasetWrapper.getImages(this.client);
                this.images = filterImages(imageWrapperList, processAllRawImages, argoSlideName);
                return true;
            } else if(datasetWrapperList.isEmpty())
                IJLogger.warn("Load OMERO images","Project "+project_wpr.getName()+ "("+projectId+") does not contain any dataset with name '"+microscopeName+"'");
            else IJLogger.warn("Load OMERO images","More than one dataset refer to "+microscopeName+" Please, group these datasets or change their name.");
            return false;

        } catch(Exception e){
            IJLogger.error("Load OMERO images","Cannot retrieve images in project "+projectId+", dataset '"+microscopeName+"'");
            return false;
        }
    }

    /**
     * Filter the list of images by removing macro images, heat maps (i.e. every image tagged with "processed" tag)
     * and optionally by removing images that have already been processed (tagged with "raw" tag).
     *
     * @param imageWrapperList List of image to filter
     * @param processAllRawImages true if you want to process all images within the dataset, regardless if
     *                            they have already been processed one.
     * @return the filtered list
     */
    private Map<String,ImageWrapper> filterImages(List<ImageWrapper> imageWrapperList, boolean processAllRawImages, String argoSlideName) {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        List<ImageWrapper> filteredWrappers = imageWrapperList.stream().filter(e -> {
            try {
                if (!processAllRawImages)
                    return (e.getTags(this.client).stream().noneMatch(t -> (t.getName().equals(Tools.RAW_TAG) || t.getName().equals(Tools.PROCESSED_TAG)))
                            && !(e.getName().contains("[macro image]")) && (e.getName().toLowerCase().contains(argoSlideName.toLowerCase())));
                else
                    return (e.getTags(this.client).stream().noneMatch(t -> t.getName().equals(Tools.PROCESSED_TAG))
                            && !(e.getName().contains("[macro image]")) && (e.getName().toLowerCase().contains(argoSlideName.toLowerCase())));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());

        Map<String,ImageWrapper> imageWrapperMap = new HashMap<>();

        filteredWrappers.forEach(e->imageWrapperMap.put(String.valueOf(e.getId()),e));
        return imageWrapperMap;
    }


    /**
     * @param key image position in the list
     * @return the {@link ImageWrapper} object of an image picked from the list of image to process.
     */
    public ImageWrapper getImageWrapper(String key){
        return this.images.get(key);
    }

    /**
     *
     * @return the {@link Client} object that handle the OMERO connection.
     */
    public Client getClient(){ return this.client; }

    @Override
    public List<ImagePlus> getImage(String key) {
        // open the image on ImageJ
        try {
            ImageWrapper impWpr = this.images.get(key);
            if(impWpr == null)
                return null;
            else
                return Collections.singletonList(impWpr.toImagePlus(this.client));
        }catch(AccessException | ServiceException | ExecutionException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getNImages() {
        return this.images.size();
    }

    @Override
    public List<String> getIDs() {
        return new ArrayList<>(images.keySet());
    }

    @Override
    public String getMicroscopeTarget() {
        return String.valueOf(this.datasetId);
    }

    @Override
    public boolean isProcessingAllRawImages() {
        return this.processAllRawImages;
    }

}
