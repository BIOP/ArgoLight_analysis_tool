package ch.epfl.biop.retrievers;

import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import ij.ImagePlus;

import java.util.ArrayList;
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
    private Map<Long,ImageWrapper> images = new HashMap<>();
    private long datasetId = -1;
    private boolean processAllRawImages = false;

    public OMERORetriever(Client client){
        this.client = client;
    }

    /**
     * Retrieve from OMERO images that need to be processed.
     *
     * @param datasetId where to look for images
     * @param processAllRawImages true if you want to process all images within the dataset, regardless if
     *                            they have already been processed one.
     * @return builder
     */
    public OMERORetriever loadRawImages(long datasetId, boolean processAllRawImages) {
        this.processAllRawImages = processAllRawImages;
        try {
            // get dataset
            DatasetWrapper datasetWrapper = this.client.getDataset(datasetId);
            this.datasetId = datasetWrapper.getId();


            // get children images
            List<ImageWrapper> imageWrapperList = datasetWrapper.getImages(this.client);

            // filter images
            this.images = filterImages(imageWrapperList, processAllRawImages);
        } catch(AccessException | ServiceException | ExecutionException e){
            IJLogger.error("Retrieve OMERO images","Cannot retrieve images in dataset "+datasetId);
        }
        return this;
    }

    /**
     * Retrieve from OMERO images that need to be processed.
     *
     * @param projectId where to look for images
     * @param datasetName name of the dataset where to look for images (corresponding to the microscope name)
     * @param processAllRawImages true if you want to process all images within the dataset, regardless if
     *                            they have already been processed one.
     * @return builder
     */
    public OMERORetriever loadRawImages(long projectId, String datasetName, boolean processAllRawImages) {
        this.processAllRawImages = processAllRawImages;
        try {
            // get the ArgoSim project
            ProjectWrapper project_wpr = this.client.getProject(projectId);

            // get the specified dataset
            List<DatasetWrapper> datasetWrapperList = project_wpr.getDatasets().stream().filter(e -> e.getName().toLowerCase().contains(datasetName)).collect(Collectors.toList());

            if (datasetWrapperList.size() == 1) {
                DatasetWrapper datasetWrapper = datasetWrapperList.get(0);
                IJLogger.info("Images downloaded from dataset : " + datasetWrapper.getName());
                this.datasetId = datasetWrapper.getId();

                List<ImageWrapper> imageWrapperList = datasetWrapper.getImages(this.client);
                this.images = filterImages(imageWrapperList, processAllRawImages);
            } else if(datasetWrapperList.isEmpty())
                IJLogger.warn("Project "+project_wpr.getName()+ "("+projectId+") does not contain any dataset with name *"+datasetName+"*");
            else IJLogger.warn("More than one dataset refer to "+datasetName+" Please, group these datasets or change their name.");

        } catch(AccessException | ServiceException | ExecutionException e){
            IJLogger.error("Retrieve OMERO images","Cannot retrieve images in project "+projectId+", dataset *"+datasetName+"*");
        }
        return this;
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
    private Map<Long,ImageWrapper> filterImages(List<ImageWrapper> imageWrapperList, boolean processAllRawImages) {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        List<ImageWrapper> filteredWrappers = imageWrapperList.stream().filter(e -> {
            try {
                if (!processAllRawImages)
                    return (e.getTags(this.client).stream().noneMatch(t -> (t.getName().equals("raw") || t.getName().equals("processed")))
                            && !(e.getName().contains("[macro image]")));
                else
                    return (e.getTags(this.client).stream().noneMatch(t -> t.getName().equals("processed"))
                            && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());

        Map<Long,ImageWrapper> imageWrapperMap = new HashMap<>();

        filteredWrappers.forEach(e->imageWrapperMap.put(e.getId(),e));
        return imageWrapperMap;
    }


    /**
     * @param key image position in the list
     * @return the {@link ImageWrapper} object of an image picked from the list of image to process.
     */
    public ImageWrapper getImageWrapper(long key){
        return this.images.get(key);
    }

    /**
     *
     * @return the {@link Client} object that handle the OMERO connection.
     */
    public Client getClient(){ return this.client; }

    @Override
    public ImagePlus getImage(long key) {
        // open the image on ImageJ
        try {
            ImageWrapper impWpr = this.images.get(key);
            if(impWpr == null)
                return null;
            else
                return impWpr.toImagePlus(this.client);
        }catch(AccessException | ServiceException | ExecutionException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getNImages() {
        return this.images.size();
    }

    @Override
    public List<Long> getIDs() {
        return new ArrayList<>(images.keySet());
    }

    @Override
    public String getParentTarget() {
        return ""+this.datasetId;
    }

    @Override
    public boolean isProcessingAllRawImages() {
        return this.processAllRawImages;
    }

}
