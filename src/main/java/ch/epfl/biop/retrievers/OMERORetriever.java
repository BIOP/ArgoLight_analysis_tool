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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class OMERORetriever implements Retriever {
    final private Client client;
    private List<ImageWrapper> images = new ArrayList<>();
    private long datasetId = -1;
    private boolean processAllRawImages = false;

    public OMERORetriever(Client client){
        this.client = client;
    }

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

    public OMERORetriever loadRawImages(long projectId, String datasetName, boolean processAllRawImages) {
        this.processAllRawImages = processAllRawImages;
        try {
            // get the ArgoSim project
            ProjectWrapper project_wpr = this.client.getProject(projectId);

            // get the specified dataset
            List<DatasetWrapper> datasetWrapperList = project_wpr.getDatasets().stream().filter(e -> e.getName().contains(datasetName)).collect(Collectors.toList());

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
     * return the list of all images that have never been processed yet.
     *
     * @param imageWrapperList
     * @param processAllRawImages
     * @return
     */
    public List<ImageWrapper> filterImages(List<ImageWrapper> imageWrapperList, boolean processAllRawImages) {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        return imageWrapperList.stream().filter(e-> {
            try {
                if(!processAllRawImages)
                    return (e.getTags(this.client).stream().noneMatch(t->(t.getName().equals("raw")||t.getName().equals("processed")))
                            && !(e.getName().contains("[macro image]")));
                else
                    return (e.getTags(this.client).stream().noneMatch(t->t.getName().equals("processed"))
                            && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());
    }


    public ImageWrapper getImageWrapper(int index){
        if(index >= this.images.size()) {
            IJLogger.error("Get image channel", "You try to access to channel "+index+ " that doesn't exists");
            return null;
        }
        return this.images.get(index);
    }


    @Override
    public ImagePlus getImage(int index) {
        if(index >= this.images.size()) {
            IJLogger.error("Get image channel", "You try to access to channel "+index+ " that doesn't exists");
            return null;
        }

        // open the image on ImageJ
        ImagePlus imp;
        try {
            imp = this.images.get(index).toImagePlus(this.client);
            return imp;
        }catch(AccessException | ServiceException | ExecutionException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getNImages() {
        return this.images.size();
    }


    @Override
    public String getParentDataset() {
        return ""+this.datasetId;
    }

    @Override
    public boolean isProcessingAllRawImages() {
        return this.processAllRawImages;
    }

}
