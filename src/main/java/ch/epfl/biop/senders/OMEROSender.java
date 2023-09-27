package ch.epfl.biop.senders;

import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.retrievers.Retriever;
import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.Tools;
import fr.igred.omero.Client;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.roi.ROIWrapper;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.process.ImageStatistics;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.EllipseData;
import omero.gateway.model.ImageData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.ROIData;
import omero.gateway.model.RectangleData;
import omero.gateway.model.ShapeData;
import omero.gateway.model.TableData;
import omero.gateway.model.TableDataColumn;
import omero.gateway.model.TagAnnotationData;
import omero.model.IObject;
import omero.model.NamedValue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Class sending to OMERO processing results coming from the analysis of the grid ArgoSlide pattern.
 */
public class OMEROSender implements Sender{
    final Client client;
    final private String date;
    final private boolean cleanTarget;
    final private String datasetId;
    private ImageWrapper imageWrapper;
    private boolean cleanParent;

    public OMEROSender(Client client, String datasetTarget, boolean cleanTarget){
        this.client = client;
        this.datasetId = datasetTarget;
        this.date = Tools.getCurrentDateAndHour();
        this.cleanTarget = cleanTarget;
        this.cleanParent = cleanTarget;
    }

    @Override
    public void initialize(ImageFile imageFile, Retriever retriever) {
        // save the new image wrapper and clean this image on OMERO if specified
        this.imageWrapper = ((OMERORetriever)retriever).getImageWrapper(imageFile.getId());
        if(this.cleanTarget)
            clean();
    }

    @Override
    public void sendTags(List<String> tags) {
        sendTags(tags, this.imageWrapper);
    }

    private void sendTags(List<String> tags, ImageWrapper imageWrapper) {
        IJLogger.info("Adding tag");

        // load group and image tags once
        List<TagAnnotationWrapper> groupTags;
        List<TagAnnotationWrapper> imageTags;
        try {
            groupTags = this.client.getTags();
            imageTags = imageWrapper.getTags(this.client);
        }catch(OMEROServerError | ServiceException | AccessException | ExecutionException e){
            IJLogger.error("Adding tag","Cannot retrieve existing & linked tags from OMERO");
            return;
        }

        // loop on tags to add
        for(String tag : tags) {
            try {
                // get the corresponding tag in the list of available tags if exists
                List<TagAnnotationWrapper> rawTag = groupTags.stream().filter(t -> t.getName().equals(tag)).collect(Collectors.toList());

                // check if the tag is already applied to the current image
                boolean isTagAlreadyExists = imageTags
                        .stream()
                        .anyMatch(t -> t.getName().equals(tag));

                // add the tag to the current image if it is not already the case
                if (!isTagAlreadyExists) {
                    imageWrapper.link(this.client, rawTag.isEmpty() ? new TagAnnotationWrapper(new TagAnnotationData(tag)) : rawTag.get(0));
                    IJLogger.info("Adding tag","The tag " + tag + " has been successfully applied on the image " + imageWrapper.getId());
                } else
                    IJLogger.info("Adding tag","The tag " + tag + " is already applied on the image " + imageWrapper.getId());

            } catch (ServiceException |  AccessException | ExecutionException e) {
                IJLogger.error("Adding tag","The tag " + tag + " could not be applied on the image " + imageWrapper.getId());
            }
        }
    }

    @Override
    public void clean() {
        IJLogger.info("Cleaning target...");

        // unlink tags
        try {
            IJLogger.info("Cleaning target", "Unlink tags from image "+this.imageWrapper.getId());
            List<TagAnnotationWrapper> tags = this.imageWrapper.getTags(this.client);
            for(TagAnnotationWrapper tag : tags){
                this.imageWrapper.unlink(this.client, tag);
            }
            IJLogger.info("Cleaning target", "Tags unlinked");
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException | OMEROServerError | InterruptedException e){
            IJLogger.error("Cleaning target", "Cannot unlink tags for image "+this.imageWrapper.getId());
        }

        // delete key-value pairs
        try {
            IJLogger.info("Cleaning target", "Removing key-values from image "+this.imageWrapper.getId());
            List<IObject> keyValues = this.client.getMetadata()
                    .getAnnotations(this.client.getCtx(), this.imageWrapper.asDataObject()).stream()
                    .filter(MapAnnotationData.class::isInstance)
                    .map(MapAnnotationData.class::cast)
                    .map(MapAnnotationData::asIObject)
                    .collect(Collectors.toList());

            if(keyValues.isEmpty()){
                IJLogger.warn("Cleaning target", "No Key-values to remove");
            }else{
                this.client.getDm().delete(this.client.getCtx(), keyValues);
                IJLogger.info("Cleaning target", "Key-values removed");
            }
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e){
            IJLogger.error("Cleaning target", "Cannot delete key-values for image "+this.imageWrapper.getId());
        }

        // delete tables
        try {
            IJLogger.info("Cleaning target", "Removing tables from image "+this.imageWrapper.getId());
            List<TableWrapper> tables = this.imageWrapper.getTables(this.client);

            if(tables.isEmpty()) {
                IJLogger.warn("Cleaning target", "No table to remove");
            } else {
                this.client.deleteTables(tables);
                IJLogger.info("Cleaning target", "Tables removed");
            }
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException | OMEROServerError | InterruptedException e){
            IJLogger.error("Cleaning target", "Cannot delete tables for image "+this.imageWrapper.getId());
        }

        // delete ROIs
        try{
            IJLogger.info("Cleaning target", "Removing ROIs from image "+this.imageWrapper.getId());
            List<IObject> rois = this.imageWrapper.getROIs(this.client).stream()
                    .map(ROIWrapper::asDataObject)
                    .map(ROIData::asIObject)
                    .collect(Collectors.toList());

            if(rois.isEmpty()){
                IJLogger.warn("Cleaning target", "No ROIs to remove");
            }else{
                this.client.getDm().delete(this.client.getCtx(), rois);
                IJLogger.info("Cleaning target", "ROIs removed");
            }


        } catch (ExecutionException | DSOutOfServiceException | DSAccessException e){
            IJLogger.error("Cleaning target", "Cannot delete ROIs for image "+this.imageWrapper.getId());
        }

        // delete parent table once
        if(this.cleanParent){
            this.cleanParent = false;
            try {
                IJLogger.info("Cleaning target", "Removing parent table from image "+this.imageWrapper.getId());
                List<DatasetWrapper> dataset = this.imageWrapper.getDatasets(this.client);
                // delete tables
                List<TableWrapper> tables = dataset.get(0).getTables(this.client);

                if(tables.isEmpty()) {
                    IJLogger.warn("Cleaning target", "No parent table to remove");
                } else {
                    this.client.deleteTables(tables);
                    IJLogger.info("Cleaning target", "Parent table removed");
                }
            } catch (ExecutionException | DSOutOfServiceException | DSAccessException | OMEROServerError | InterruptedException e){
                IJLogger.error("Cleaning target", "Cannot delete parent tables for image "+this.imageWrapper.getId());
            }
        }
    }

    @Override
    /**
     * target : dataset ID
     */
    public void sendHeatMaps(ImagePlus imp) {
        IJLogger.info("Sending heatmap");
        String home = Prefs.getHomeDir();

        // save the image on the computer first and get the generated file
        File localImageFile = saveHeatMapLocally(imp, home);
        if(localImageFile == null) {
            IJLogger.error("Saving temporary heatMap","Cannot save temporary image "+imp.getTitle()+" in " +home);
            return;
        }

        try {
            // Import image on OMERO
            List<Long> analysisImage_omeroID = this.client.getDataset(Long.parseLong(this.datasetId)).importImage(this.client, localImageFile.toString());
            ImageWrapper analysisImage_wpr = this.client.getImage(analysisImage_omeroID.get(0));
            IJLogger.info("Sending heatmap", imp.getTitle() + ".tif" + " was uploaded to OMERO with ID : " + analysisImage_omeroID);

            List<String> tags = new ArrayList<String>(){{
                add(Tools.PROCESSED_TAG);
                add(Tools.ARGOLIGHT_TAG);
                add((String)imp.getProperty(Tools.PROCESSED_FEATURE));
            }};
            sendTags(tags, analysisImage_wpr);

        } catch (ServiceException | AccessException | ExecutionException | OMEROServerError e) {
            IJLogger.error("Sending heatmap", "Cannot upload heat maps on OMERO");
        }

        // delete the file after upload
        boolean hasBeenDeleted = localImageFile.delete();
        if(hasBeenDeleted)
            IJLogger.info("Upload heatMap","Temporary image deleted");
        else IJLogger.error("Upload heatMap","Cannot delete temporary image from "+localImageFile.getAbsolutePath());
    }

    @Override
    public void sendKeyValues(Map<String, String> keyValues) {
        IJLogger.info("Sending Key-values");
        if(!keyValues.isEmpty()) {
            List<NamedValue> namedValues = new ArrayList<>();
            keyValues.forEach((key, value) -> namedValues.add(new NamedValue(key, value)));

            // create a new MapAnnotation
            MapAnnotationWrapper newKeyValues = new MapAnnotationWrapper(namedValues);
            newKeyValues.setNameSpace("ArgoLight analysis "+this.date); // "openmicroscopy.org/omero/client/mapAnnotation"
            try {
                // upload key-values on OMERO
                this.imageWrapper.link(this.client, newKeyValues);
                IJLogger.info("Sending Key-values","Key-values have been successfully applied on the image " + imageWrapper.getId());
            } catch (ServiceException | AccessException | ExecutionException e) {
                IJLogger.error("Sending Key-Values", "Key-values could not be uploaded and linked to the image " + this.imageWrapper.getId());
            }
        } else IJLogger.warn("Sending Key-Values", "There is no key-values to send to OMERO");
    }

    @Override
    public void sendGridPoints(List<Roi> rois, int channelId, String roiTitle) {
        IJLogger.info("Sending "+roiTitle + " ROIs");
        if (!(rois.isEmpty())) {
            // create one shape per imageJ ROI
            List<ShapeData> gridShapes = convertIJRoisToShapeData(rois, channelId);

            // add all individual shapes in the same OMERO ROI
            ROIData roiGrid = new ROIData();
            gridShapes.forEach(roiGrid::addShapeData);

            List<ROIWrapper> omeroRois = new ArrayList<ROIWrapper>(){{add(new ROIWrapper(roiGrid));}};
            omeroRois.get(0).setName(this.date + "_"+roiTitle);
            try {
                // save ROI on OMERO
                this.imageWrapper.saveROIs(this.client, omeroRois);
                IJLogger.info("Sending "+roiTitle + " ROIs","The ROIs have been successfully uploaded and linked to the image " + imageWrapper.getId());
            } catch (ExecutionException | DSOutOfServiceException | DSAccessException e){
                IJLogger.error("Sending "+roiTitle + " ROIs","Error during saving ROIs on OMERO.");
            }
        } else IJLogger.info("Sending "+roiTitle + " ROIs","There is no Annotations to upload on OMERO");

    }

    @Override
    public void sendPCCTable(List<List<Double>> pccValues, int nChannels){
        IJLogger.info("Sending PCC table");
        // build the table
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();

        List<Integer> chs1 = new ArrayList<>();
        List<Integer> chs2 = new ArrayList<>();

        for(int i = 0; i < nChannels-1; i++)
            for(int j = i+1; j < nChannels; j++){
                chs1.add(i);
                chs2.add(j);
            }

        // add headers and values
        int i = 0;
        for(List<Double> channelPair : pccValues){
            columns.add(new TableDataColumn("ch"+chs1.get(i)+"_ch"+chs2.get(i), i++, Double.class));
            measurements.add(new ArrayList<>(channelPair));
        }

        if(!columns.isEmpty()) {
            // send the table to OMERO
            try {
                TableWrapper tableWrapper = new TableWrapper(new TableData(columns, measurements));
                tableWrapper.setName(this.date + "_PCC_table");
                this.imageWrapper.addTable(this.client, tableWrapper);
                IJLogger.info("Sending PCC table","PCC table has been successfully uploaded and linked to the image " + imageWrapper.getId());
            } catch (DSAccessException | ServiceException | ExecutionException e) {
                IJLogger.error("Sending PCC table","Cannot add the results table to image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId());
            }
        } else IJLogger.warn("Saving PCC table","No results to save");
    }

    @Override
    public void sendResultsTable(List<List<Double>> values, List<Integer> channelIdList, boolean createNewTable, String tableName){
        IJLogger.info("Sending "+tableName+" table");
        TableWrapper tableWrapper;
        // get the last OMERO table
        TableWrapper table = getOmeroTable(this.client, this.imageWrapper, tableName);

        // populate existing table or create a new one
        if(!createNewTable && table != null)
            tableWrapper = addNewColumnsToTable(table, values, channelIdList, this.date);
        else tableWrapper = createNewTable(values, channelIdList, this.date);

        // send table to OMERO
        try {
            tableWrapper.setName(tableName);
            this.imageWrapper.addTable(this.client, tableWrapper);
            if(table != null) this.client.deleteTable(table);
            IJLogger.info("Sending "+tableName+" table",tableName+" table has been successfully uploaded and linked to the image " + imageWrapper.getId());
        } catch (DSAccessException | ServiceException | ExecutionException | OMEROServerError | InterruptedException e) {
            IJLogger.error("Sending "+tableName+" table","Cannot add the "+tableName+" table to image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId());
        }
    }

    @Override
    public void populateParentTable(Retriever retriever, Map<String, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable) {
        IJLogger.info("Update parent table...");
        // format data
        List<Object[]> fullRows = new ArrayList<>();
        for (Map.Entry<String, List<List<Double>>> IdEntry : summary.entrySet()) {
            List<List<Double>> allChannelMetrics = IdEntry.getValue();

            // convert to Object type
            List<List<Object>> allChannelMetricsAsObject = new ArrayList<>();
            for (List<Double> objects : allChannelMetrics)
                allChannelMetricsAsObject.add(new ArrayList<>(objects));

            String omeroID = IdEntry.getKey().split(Tools.SEPARATION_CHARACTER)[1];
            ImageWrapper image = ((OMERORetriever) retriever).getImageWrapper(omeroID);
            for (List<Object> metrics : allChannelMetricsAsObject) {
                metrics.add(0, image.getName());
                metrics.add(0, image.asDataObject());
                fullRows.add(metrics.toArray());
            }
        }

        try {
            // get the current dataset
            DatasetWrapper dataset = this.client.getDataset(Long.parseLong(this.datasetId));

            if(populateExistingTable) {
                // get existing table
                TableWrapper tableWrapper = getLastOmeroTable(this.client, dataset);

                // apply the adding/replacement policy
                if(tableWrapper == null)
                    addNewParentTable(fullRows, headers, dataset, this.date);
                else replaceExistingParentTable(fullRows, dataset, tableWrapper, this.date);
            } else
                addNewParentTable(fullRows, headers, dataset, this.date);

            IJLogger.info("Update parent table","New analysis summaries have been uploaded and linked to dataset " + dataset.getName() + " : " + dataset.getId());
        } catch (DSAccessException | ServiceException | ExecutionException e) {
            IJLogger.error("Update parent table","Cannot add the summaries to the parent table on dataset " + this.datasetId);
        }
    }

    /**
     * Create a new OMERO table
     * @param values data to send, formatted in a list of list (list of columns)
     * @param channelIdList list of channels
     * @param date current date of processing
     * @return the new table wrapper
     */
    private TableWrapper createNewTable(List<List<Double>> values, List<Integer> channelIdList, String date) {
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();
        int i = 0;

        if(values.size() > 0) {
            // add the first column with the image data (linkable on OMERO)
            columns.add(new TableDataColumn("Image ID", i++, ImageData.class));
            List<Object> imageData = new ArrayList<>();

            for (Double ignored : values.get(0)) {
                imageData.add(this.imageWrapper.asDataObject());
            }
            measurements.add(imageData);

            // add the second column with the ring id (from top-left to bottom-right)
            columns.add(new TableDataColumn("Ring ID", i++, Long.class));
            List<Object> ids = new ArrayList<>();
            LongStream.range(0, values.get(0).size()).forEach(ids::add);
            measurements.add(ids);

            for (int j = 0; j < values.size(); j++) {
                columns.add(new TableDataColumn("ch" + channelIdList.get(j) + "_" + date, i++, Double.class));
                measurements.add(new ArrayList<>(values.get(j)));
            }
        }
        return new TableWrapper(new TableData(columns, measurements));
    }


    /**
     * Populate an existing table with new columns of data
     *
     * @param tableToPopulate existing table
     * @param values data to add
     * @param channelIdList list of channels
     * @param date current date of processing
     * @return A new table wrapper containing all previous and new data
     */
    private TableWrapper addNewColumnsToTable(TableWrapper tableToPopulate, List<List<Double>> values, List<Integer> channelIdList, String date){
        TableData tableData = tableToPopulate.createTable();
        List<TableDataColumn> columns = new ArrayList<>(Arrays.asList(tableData.getColumns()));
        List<List<Object>> measurements = new ArrayList<>();

        Object[][] data = tableData.getData();
        for (Object[] datum : data) measurements.add(Arrays.asList(datum));
        int i = data.length;

        if(values.size() > 0) {
            for (int j = 0; j < values.size(); j++) {
                columns.add(new TableDataColumn("ch" + channelIdList.get(j) + "_" + date, i++, Double.class));
                measurements.add(new ArrayList<>(values.get(j)));
            }
        }
        return new TableWrapper(new TableData(columns, measurements));
    }


    /**
     * create a new summary table
     * @param fullRows metrics summary as a list of array (row)
     * @param headers table headers
     * @param repoWrapper OMERO container (e.g DatasetData)
     * @param date current date of processing
     */
    private void addNewParentTable(List<Object[]> fullRows, List<String> headers, GenericRepositoryObjectWrapper<?> repoWrapper, String date) {
        try {
            // create a new table
            TableWrapper tableWrapper = new TableWrapper(headers.size() + 2, date + "_" + repoWrapper.getName() + "_Table");

            // set headers
            tableWrapper.setColumn(0, "Image", ImageData.class);
            tableWrapper.setColumn(1, "Label", String.class);

            int i = 2;
            for (String header : headers)
                tableWrapper.setColumn(i++, header, Double.class);

            // set table size
            tableWrapper.setRowCount(fullRows.size());

            // add metrics to the table
            for (Object[] row : fullRows) tableWrapper.addRow(row);

            // add the table to OMERO
            repoWrapper.addTable(this.client, tableWrapper);

        } catch (DSAccessException | ServiceException | ExecutionException e) {
            IJLogger.error("Cannot add the results table to image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId());
        }
    }

    /**
     * read the existing summary parent table and append new summary results at the bottom.
     *
     * @param fullRows metrics summary as a list of array (row)
     * @param repoWrapper OMERO container (e.g DatasetData)
     * @param tableWrapper the existing
     * @param date current date of processing
     */
    private void replaceExistingParentTable(List<Object[]> fullRows, GenericRepositoryObjectWrapper<?> repoWrapper, TableWrapper tableWrapper, String date){
        try {
            // get the table size
            int nExistingRows = tableWrapper.getRowCount();

            // set new table size
            tableWrapper.setRowCount(nExistingRows + fullRows.size());

            // add metrics to the table
            for (Object[] row : fullRows) tableWrapper.addRow(row);

            // duplicate the table
            TableWrapper newTable = new TableWrapper(tableWrapper.createTable());

            // set table name (with the new date)
            newTable.setName(date + "_" + repoWrapper.getName() + "_Table");

            // add the new table
            repoWrapper.addTable(this.client, newTable);

            // delete the previous table
            this.client.deleteTable(tableWrapper);

        } catch (ServiceException | AccessException | ExecutionException e) {
            IJLogger.error("Cannot add results to previous table " + tableWrapper.getName() + " : " + tableWrapper.getId());
        } catch (OMEROServerError | InterruptedException e ){
            IJLogger.error("Cannot delete previous table " + tableWrapper.getName() + " : " + tableWrapper.getId());
        }
    }

    /**
     * save an image locally on the computer and returns the saved file
     *
     * @param imp the imagePlus to save
     * @param folder the location where to save it
     * @return the saved file
     */
    public static File saveHeatMapLocally(ImagePlus imp, String folder){
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(folder,imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

        // check if the image was correctly saved
       if(!hasBeenSaved)
           return null;
       return analysisImage_output_path;
    }

    /**
     * Convert imageJ circles and rectangle ROI to ellipse and rectangle OMERO ROI
     *
     * @param rois to convert
     * @param channelId channel id where they rely on
     * @return the converted ROIs
     */
    private List<ShapeData> convertIJRoisToShapeData(List<Roi> rois, int channelId){
        List<ShapeData> shapes = new ArrayList<>();

        for(int i = 0; i< rois.size(); i++) {
            Roi roi = rois.get(i);
            int type = roi.getType();
            ImageStatistics roiStat = roi.getStatistics();
            switch (type) {
                case Roi.OVAL:
                    EllipseData ellipse = new EllipseData(roiStat.xCentroid,
                            roiStat.yCentroid,
                            roiStat.roiWidth / 2,
                            roiStat.roiHeight / 2);
                    ellipse.setText(i+":child");
                    ellipse.setC(channelId);
                    ellipse.getShapeSettings().setStroke(roi.getStrokeColor());
                    ellipse.getShapeSettings().setFill(roi.getFillColor());
                    shapes.add(ellipse);
                    break;
                case Roi.RECTANGLE:
                    // Build the OMERO object
                    RectangleData rectangle = new RectangleData(roiStat.roiX, roiStat.roiY, roiStat.roiWidth, roiStat.roiHeight);
                    // Write in comments the type of PathObject as well as the assigned class if there is one
                    rectangle.setText(i+":child");
                    // set the ROI position in the image
                    rectangle.setC(channelId);
                    shapes.add(rectangle);
            }
        }

        return shapes;
    }

    /**
     * get the last table attached to an image/dataset/project...
     *
     * @param client
     * @param repoWrapper
     * @return
     */
    private static TableWrapper getLastOmeroTable(Client client, GenericRepositoryObjectWrapper<?> repoWrapper) {
        try {
            // Prepare a Table
            List<TableWrapper> repoTables = repoWrapper.getTables(client);

            if (!repoTables.isEmpty()) {
                // get al names
                List<String> names = repoTables.stream().map(TableWrapper::getName).collect(Collectors.toList());

                // get dates
                List<String> orderedDate = new ArrayList<>();
                names.forEach(name-> orderedDate.add(name.split("_")[0]));

                // sort dates in reverse order (larger to smaller date)
                orderedDate.sort(Comparator.reverseOrder());

                // take the last table
                return repoTables.stream().filter(e->e.getName().contains(orderedDate.get(0))).collect(Collectors.toList()).get(0);
            } else
                return null;
        }catch(ServiceException | AccessException | ExecutionException e){
            IJLogger.error("Could not get tables attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
            return null;
        }
    }

    /**
     * Read an OMERO table attached to container
     *
     * @param client
     * @param repoWrapper
     * @param tableName
     * @return
     */
    private static TableWrapper getOmeroTable(Client client, GenericRepositoryObjectWrapper<?> repoWrapper, String tableName){
        try {
            List<TableWrapper> tableList = repoWrapper.getTables(client).stream().filter(e -> e.getName().contains(tableName)).collect(Collectors.toList());
            if(!tableList.isEmpty()){
                return tableList.get(0);
            }
           return null;
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException e){
            IJLogger.error("Could not get table "+tableName+" attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
            return null;
        }
    }
}
