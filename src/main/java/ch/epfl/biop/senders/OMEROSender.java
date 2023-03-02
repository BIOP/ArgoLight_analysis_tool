package ch.epfl.biop.senders;

import ch.epfl.biop.IJLogger;
import ch.epfl.biop.ImageChannel;
import ch.epfl.biop.ImageFile;
import ch.epfl.biop.OMERORetriever;
import ch.epfl.biop.Retriever;
import com.google.common.primitives.Ints;
import fr.igred.omero.Client;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.roi.EllipseWrapper;
import fr.igred.omero.roi.GenericShapeWrapper;
import fr.igred.omero.roi.LineWrapper;
import fr.igred.omero.roi.PolygonWrapper;
import fr.igred.omero.roi.PolylineWrapper;
import fr.igred.omero.roi.ROIWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.RoiEncoder;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.ROIFacility;
import omero.gateway.model.EllipseData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PolygonData;
import omero.gateway.model.PolylineData;
import omero.gateway.model.ROIData;
import omero.gateway.model.RectangleData;
import omero.gateway.model.ShapeData;
import omero.gateway.model.TableData;
import omero.gateway.model.TableDataColumn;
import omero.gateway.model.TagAnnotationData;
import omero.model.NamedValue;
import org.apache.commons.io.FileUtils;

import java.awt.geom.Point2D;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class OMEROSender implements Sender{
    final Client client;
    private ImageWrapper imageWrapper;
    final private static String PROCESSED_FEATURE = "feature";

    public OMEROSender(Client client){
        this.client = client;
    } //TODO ajouter le boolean "local heat map saving"

    @Override
    public void sendResults(ImageFile imageFile, ImageWrapper imageWrapper, String target, boolean savingHeatMaps) {
        List<ImageChannel> channels = imageFile.getChannels();
        this.imageWrapper = imageWrapper;

        sendKeyValues(imageFile.getKeyValues());
        sendTags(imageFile.getTags(), this.imageWrapper);

        for(ImageChannel channel : channels){
            sendGridPoints(channel.getGridRings(), channel.getIdealGridRings(), channel.getId());
            sendKeyValues(channel.getKeyValues());
            sendResultsTable(channel.getFieldDistortion(), channel.getFieldUniformity(), channel.getFWHM(), channel.getId());

            if(savingHeatMaps)
                sendHeatMaps(channel, imageFile.getImgNameWithoutExtension(), target);
        }

    }

    public void sendTags(List<String> tags, ImageWrapper imageWrapper) {
        for(String tag : tags) {
            try {
                // get the corresponding tag in the list of available tags if exists
                List<TagAnnotationWrapper> rawTag = this.client.getTags().stream().filter(t -> t.getName().equals(tag)).collect(Collectors.toList());

                // check if the tag is already applied to the current image
                boolean isTagAlreadyExists = imageWrapper.getTags(this.client)
                        .stream()
                        .anyMatch(t -> t.getName().equals(tag));

                // add the tag to the current image if it is not already the case
                if (!isTagAlreadyExists) {
                    imageWrapper.addTag(this.client, rawTag.isEmpty() ? new TagAnnotationWrapper(new TagAnnotationData(tag)) : rawTag.get(0));
                    IJLogger.info("Adding tag","The tag " + tag + " has been successfully applied on the image " + imageWrapper.getId());
                } else
                    IJLogger.info("Adding tag","The tag " + tag + " is already applied on the image " + imageWrapper.getId());

            } catch (ServiceException | OMEROServerError | AccessException | ExecutionException e) {
                IJLogger.error("Adding tag","The tag " + tag + " could not be applied on the image " + imageWrapper.getId());
            }
        }
    }

    @Override
    /**
     * target : dataset ID
     */
    public void sendHeatMaps(ImageChannel channel, String parentImageName, String target) {
        List<ImagePlus> heatMaps = new ArrayList<>();
        heatMaps.add(channel.getFWHMHeatMap(parentImageName));
        heatMaps.add(channel.getFieldDistortionHeatMap(parentImageName));
        heatMaps.add(channel.getFieldUniformityHeatMap(parentImageName));

        String home = Prefs.getHomeDir();
        String tmpFolderPath = home + File.separator + "tmp";

        if(new File(tmpFolderPath).mkdir()) {
            try {
                for(ImagePlus heatMap : heatMaps) {
                    // save the image on the computer first and get the generate file
                    File localImageFile = saveHeatMapLocally(heatMap, tmpFolderPath);
                    // Import image on OMERO
                    List<Long> analysisImage_omeroID = client.getDataset(Long.parseLong(target)).importImage(client, localImageFile.toString());
                    ImageWrapper analysisImage_wpr = client.getImage(analysisImage_omeroID.get(0));
                    IJLogger.info("Upload heatMap", heatMap.getTitle() + ".tif" + " was uploaded to OMERO with ID : " + analysisImage_omeroID);

                    List<String> tags = new ArrayList<String>(){{
                        add("processed");
                        add("argolight");
                        add((String)heatMap.getProperty(PROCESSED_FEATURE));
                    }};
                    sendTags(tags, analysisImage_wpr);
                }

            } catch (ServiceException | AccessException | ExecutionException | OMEROServerError e) {
                IJLogger.error("Upload heatMap", "Cannot upload heat maps on OMERO");
            }

            // delete the whole folder
            try {
                // delete the file after upload
                FileUtils.deleteDirectory(new File(tmpFolderPath));
                IJLogger.info("Upload heatMap","Temporary folder deleted");
            } catch (IOException e){
                IJLogger.error("Upload heatMap","Cannot delete temporary folder "+tmpFolderPath);
            }

        } else IJLogger.error("Upload heatMap","Cannot create temporary folder "+tmpFolderPath);
    }

    @Override
    public void sendKeyValues(Map<String, String> keyValues) {
        if(!keyValues.isEmpty()) {
            List<NamedValue> namedValues = new ArrayList<>();
            keyValues.forEach((key, value) -> namedValues.add(new NamedValue(key, value)));

            // create a new MapAnnotation
            MapAnnotationWrapper newKeyValues = new MapAnnotationWrapper(namedValues);
            newKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");
            try {
                // upload key-values on OMERO
                this.imageWrapper.addMapAnnotation(this.client, newKeyValues);
            } catch (ServiceException | AccessException | ExecutionException e) {
                IJLogger.error("Adding Key-Values", "KeyValues could not be added on the image " + this.imageWrapper.getId());
            }
        } else IJLogger.warn("Send Key-Values", "There is no key-values to send to OMERO");
    }

    @Override
    public void sendGridPoints(List<Roi> grid, List<Roi> ideal, int channelId) {
        // import ROIs on OMERO
        if (!(grid.isEmpty()) && !(ideal.isEmpty())) {
            String currentGrid = "measuredGrid";
            List<ShapeData> gridShapes = convertIJRoisToShapeData(grid, channelId, currentGrid);
            currentGrid = "idealGrid";
            List<ShapeData> idealGridShapes = convertIJRoisToShapeData(ideal, channelId, currentGrid);

            ROIData roiGrid = new ROIData();
            gridShapes.forEach(roiGrid::addShapeData);

            ROIData roiIdealGrid = new ROIData();
            idealGridShapes.forEach(roiIdealGrid::addShapeData);

            List<ROIWrapper> omeroRois = new ArrayList<ROIWrapper>(){{add(new ROIWrapper(roiGrid)); add(new ROIWrapper(roiIdealGrid));}};
            try {
                // save ROIs
                this.imageWrapper.saveROIs(this.client, omeroRois);
            } catch (ExecutionException | DSOutOfServiceException | DSAccessException e){
                IJLogger.error("ROI Saving","Error during saving ROIs on OMERO.");
            }
        } else {
            IJLogger.info("Upload annotations","There is no Annotations to upload on OMERO");
        }

        // pour le local sender ==> code du ROI manager
       /* try {
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
            out = new DataOutputStream(new BufferedOutputStream(zos));
            RoiEncoder re = new RoiEncoder(out);
            for (int i=0; i<indexes.length; i++) {
                IJ.showProgress(i, indexes.length);
                String label = getUniqueName(names, indexes[i]);
                Roi roi = (Roi)rois.get(indexes[i]);
                if (IJ.debugMode) IJ.log("saveMultiple: "+i+"  "+label+"  "+roi);
                if (roi==null) continue;
                if (!label.endsWith(".roi")) label += ".roi";
                zos.putNextEntry(new ZipEntry(label));
                re.write(roi);
                out.flush();
            }
            out.close();
        } catch (IOException e) {
            errorMessage = ""+e;
            error(errorMessage);
            return false;
        } finally {
            if (out!=null)
                try {out.close();} catch (IOException e) {}
        }*/

    }

    @Override
    public void sendResultsTable(List<Double> fieldDistortion, List<Double> fieldUniformity, List<Double> fwhm, int channelId) {
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();
        int i = 0;

        // add the first column with the image data (linkable on OMERO)
        columns.add(new TableDataColumn("Image ID",i++, ImageData.class));
        List<Object> imageData = new ArrayList<>();

        for (Double ignored : fieldDistortion) {
            imageData.add(this.imageWrapper.asImageData());
        }
        measurements.add(imageData);

        // add the second column with the ring id (from top-left to bottom-right)
        columns.add(new TableDataColumn("Ring ID",i++, ImageData.class));
        List<Object> ids = new ArrayList<>();
        IntStream.range(0, fieldDistortion.size()).forEach(ids::add);
        measurements.add(ids);

        // add columns with computed parameters
        List<Object> fieldDistortionObject = new ArrayList<>(fieldDistortion);
        List<Object> fieldUniformityObject = new ArrayList<>(fieldUniformity);
        List<Object> fwhmObject = new ArrayList<>(fwhm);

        columns.add(new TableDataColumn("Field Distortion", i++, Double.class));
        measurements.add(fieldDistortionObject);
        columns.add(new TableDataColumn("Field Uniformity", i++, Double.class));
        measurements.add(fieldUniformityObject);
        columns.add(new TableDataColumn("FWHM", i, Double.class));
        measurements.add(fwhmObject);

        // send the omero Table
        try {
            TableWrapper tableWrapper = new TableWrapper(new TableData(columns, measurements));
            tableWrapper.setName("Results_table_ch"+channelId);
            this.imageWrapper.addTable(client, tableWrapper);
            IJLogger.info("Results table for image "+this.imageWrapper.getName() + " : "+this.imageWrapper.getId()+" has been uploaded");
        }catch(DSAccessException | ServiceException | ExecutionException e){
            IJLogger.error("Cannot add the results table to image "+this.imageWrapper.getName() + " : "+this.imageWrapper.getId());
        }
    }

    @Override
    public void populateParentTable() {



    }

    /**
     * save an image locally on the computer and returns the saved file
     *
     * @param imp
     * @param folder
     * @return
     */
    public static File saveHeatMapLocally(ImagePlus imp, String folder){
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(folder,imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

        // check if the image was correctly saved
       //if(hasBeenSaved) IJLogger.info("Saving heatmaps locally"+imp.getTitle()+".tif"+" was saved in : "+ folder);
        //else IJ.log("[ERROR] [DataManagement][saveHeatMapLocally] -- Cannot save "+imp.getTitle()+ " in "+folder);

        return analysisImage_output_path;
    }

    private List<ShapeData> convertIJRoisToShapeData(List<Roi> rois, int channelId, String comment){
        List<ShapeData> shapes = new ArrayList<>();

        for(int i = 0; i< rois.size(); i++) {
            Roi roi = rois.get(i);
            int type = roi.getType();
            switch (type) {
                case Roi.OVAL:
                    EllipseData ellipse = new EllipseData(roi.getStatistics().xCentroid,
                            roi.getStatistics().yCentroid,
                            roi.getStatistics().roiWidth / 2,
                            roi.getStatistics().roiHeight / 2);
                    ellipse.setText(comment+":"+i+":child");
                    ellipse.setC(channelId);
                    shapes.add(ellipse);
                    break;
                case Roi.RECTANGLE:
                    // Build the OMERO object
                    RectangleData rectangle = new RectangleData(roi.getStatistics().roiX, roi.getStatistics().roiY, roi.getStatistics().roiWidth, roi.getStatistics().roiHeight);
                    // Write in comments the type of PathObject as well as the assigned class if there is one
                    rectangle.setText(comment+":"+i+":child");
                    // set the ROI position in the image
                    rectangle.setC(channelId);
                    shapes.add(rectangle);
            }
        }

        return shapes;
    }
}
