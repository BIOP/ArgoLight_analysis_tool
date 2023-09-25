package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.retrievers.Retriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.utils.Tools;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.RoiEnlarger;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Intermediate class that process image and send results to the right place
 */
public class Processing {

    /**
     * Runs the processing on the current image and send results locally or to OMERO
     *
     * @param retriever retriever use to get data
     * @param savingHeatMaps true if you want to save heatmaps
     * @param sender sender object (local or OMERO sender)
     * @param userSigma user defined value of sigma for gaussian blurring
     * @param userMedianRadius user defined value of median radius for median filtering
     * @param userThresholdingMethod user defined thresholding method used
     * @param userParticleThreshold user defined value of the threshold on particle size
     * @param userRingRadius user defined value of the analysis circle radius around each ring
     * @param argoslide The name of the argoslide selected in the GUI
     * @param argoSpacing distance between two rings in the grid
     * @param argoFOV FoV of the pattern B of the argoslide
     * @param argoNPoints number of rings in the same line
     */
    public static void run(Retriever retriever, boolean savingHeatMaps, Sender sender, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius, String argoslide, int argoSpacing, int argoFOV, int argoNPoints){
        Map<String, List<List<Double>>> summaryMap = new HashMap<>();
        List<String> headers = new ArrayList<>();
        List<String> IDs = retriever.getIDs();

        // loop on each image file, based on its ID (OMERO ID or UUID for local image)
        for (String Id : IDs) {
            // get the image
            List<ImagePlus> impList = retriever.getImage(Id);

            // loop on image series
            for (int serie = 0; serie < impList.size(); serie++) {
                ImagePlus imp = impList.get(serie);
                if (imp == null)
                    continue;

                // define a unique ID per serie
                String imgTitle = imp.getTitle();
                String uniqueID = imgTitle + Tools.SEPARATION_CHARACTER + Id;

                try {
                    // create a new ImageFile object
                    IJLogger.info("Working on image " + imgTitle);
                    ImageFile imageFile = new ImageFile(imp, Id, imgTitle, serie + 1);

                    boolean isOldProtocol = false;

                    // choose the right ArgoLight processing
                    if (!imageFile.getArgoSlideName().contains("ArgoSimOld")) {
                        ArgoSlideProcessing.run(imageFile, userSigma, userMedianRadius, userThresholdingMethod,
                                userParticleThreshold, userRingRadius, argoslide, argoSpacing, argoFOV, argoNPoints);
                    } else {
                        ArgoSlideOldProcessing.run(imageFile);
                        isOldProtocol = true;
                    }

                    IJLogger.info("End of processing");
                    IJLogger.info("Sending results ... ");

                    // send image results (metrics, rings, tags, key-values)
                    sender.initialize(imageFile, retriever);
                    sender.sendTags(imageFile.getTags());
                    sendResults(sender, imageFile, savingHeatMaps, isOldProtocol);

                    // metrics summary to populate parent table
                    Map<List<String>, List<List<Double>>> allChannelMetrics = imageFile.summaryForParentTable();
                    headers = new ArrayList<>(allChannelMetrics.keySet()).get(0);
                    if (!allChannelMetrics.values().isEmpty())
                        summaryMap.put(uniqueID, allChannelMetrics.values().iterator().next());

                } catch (Exception e) {
                    IJLogger.error("An error occurred during processing ; cannot analyse the image " + imgTitle);
                }
            }
        }

        // populate parent table with summary results
        sender.populateParentTable(retriever, summaryMap, headers, !retriever.isProcessingAllRawImages());
    }

    /**
     * save processing results
     * @param sender
     * @param imageFile
     * @param savingHeatMaps
     * @param isOldProtocol
     */
    private static void sendResults(Sender sender, ImageFile imageFile, boolean savingHeatMaps, boolean isOldProtocol){
        Map<String, String> keyValues = imageFile.getKeyValues();

        // send PCC table
        if (imageFile.getNChannels() > 1)
            sender.sendPCCTable(imageFile.getPCC(), imageFile.getNChannels());

        List<List<Double>> distortionValues = new ArrayList<>();
        List<List<Double>> uniformityValues = new ArrayList<>();
        List<List<Double>> fwhmValues = new ArrayList<>();
        List<Integer> chIds = new ArrayList<>();

        for (int i = 0; i < imageFile.getNChannels(); i++) {
            ImageChannel channel = imageFile.getChannel(i);
            // get channel keyValues
            keyValues.putAll(channel.getKeyValues());
            // send Rois
            sender.sendGridPoints(channel.getGridRings(), channel.getId(), "measuredGrid");
            sender.sendGridPoints(channel.getIdealGridRings(), channel.getId(), "idealGrid");

            // collect metrics for each channel
            distortionValues.add(channel.getFieldDistortion());
            uniformityValues.add(channel.getFieldUniformity());
            fwhmValues.add(channel.getFWHM());
            chIds.add(channel.getId());

            // send heat maps
            if (savingHeatMaps) {
                if(isOldProtocol || !imageFile.getImagedFoV().equals("partialFoV")) sender.sendHeatMaps(channel.getFieldDistortionHeatMap(imageFile.getImgNameWithoutExtension()));
                if(isOldProtocol || !imageFile.getImagedFoV().equals("partialFoV")) sender.sendHeatMaps(channel.getFieldUniformityHeatMap(imageFile.getImgNameWithoutExtension()));
                if(isOldProtocol || !imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFWHMHeatMap(imageFile.getImgNameWithoutExtension()));
            }
        }

        // send results table
        if(isOldProtocol || !imageFile.getImagedFoV().equals("partialFoV")) sender.sendResultsTable(distortionValues, chIds, false, "Field_distortion");
        if(isOldProtocol || !imageFile.getImagedFoV().equals("partialFoV")) sender.sendResultsTable(uniformityValues, chIds, false, "Field_uniformity");
        if(isOldProtocol || !imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(fwhmValues, chIds, false, "FWHM");

        // send key values
        if(sender instanceof LocalSender) {
            keyValues.put("Image_ID", imageFile.getId());
            keyValues.put("Image_Title", imageFile.getTitle());
            keyValues.put("Image_Serie", String.valueOf(imageFile.getSerie()));
        }
        sender.sendKeyValues(keyValues);
    }


    /**
     * find the central cross of the ArgoLight pattern B
     *
     * @param imp
     * @param rm
     * @return
     */
    protected static Roi getCentralCross(ImagePlus imp, RoiManager rm, double imagePixelSize, String segMethod, int argoFOV){
        rm.reset();

        // make sure no ROI is left on the imp
        IJ.run(imp, "Select None", "");
        IJ.run(imp,"Remove Overlay", "");

        // Detect Cross in the center of the FOV
        ImagePlus mask_imp = imp.duplicate();
        IJ.setAutoThreshold(mask_imp, segMethod+" dark");
        IJ.run(mask_imp, "Convert to Mask", "");
        IJ.run(mask_imp, "Analyze Particles...", "size="+(2.5/imagePixelSize)+"-Infinity add");

        // get central ROIs while excluding bounding semi-crosses
        double gridFactor = argoFOV/(4*imagePixelSize); // size of the central window depend on the pixel size
        List<Roi> rois = Arrays.stream(rm.getRoisAsArray()).filter(roi -> ((roi.getStatistics().xCentroid < imp.getWidth()/2.0 + gridFactor
                && roi.getStatistics().xCentroid > imp.getWidth()/2.0 - gridFactor)
                && (roi.getStatistics().yCentroid < imp.getHeight()/2.0 + gridFactor
                && roi.getStatistics().yCentroid > imp.getHeight()/2.0 - gridFactor))).collect(Collectors.toList());

        // get the ROI with larger width corresponding to the central cross
        Roi crossRoi = rois.stream().max(Comparator.comparing(roi -> roi.getStatistics().roiWidth)).get();

        rm.reset();
        return new Roi(crossRoi.getBounds());
    }

    /**
     * generate a list of point with small rings coordinates.
     *
     * @param DoGImage
     * @param crossRoi
     * @param pixelSizeImage
     * @return
     */
    protected static List<Point2D> getGridPoint(ImagePlus DoGImage, Roi crossRoi, double pixelSizeImage, double sigma,
                                                double medianRadius, double prtThreshold, String segMethod,
                                                int argoFOV, int argoSpacing, int argoNPoints){
        int nPoints;
        Roi enlargedRectangle;

        // compute the number of points on each side of the center to adapt the size of the large rectangle
        // if the FoV of the image is smaller than the pattern FoV => limited number of points
        if((DoGImage.getWidth()*pixelSizeImage < (argoFOV + 4)) && (DoGImage.getHeight()*pixelSizeImage < (argoFOV + 4))){  // 100um of field of view + 2 um on each side
            nPoints = (int)Math.min(Math.floor(((DoGImage.getWidth()*pixelSizeImage/2)-2 )/argoSpacing), Math.floor(((DoGImage.getHeight()*pixelSizeImage/2)-2)/argoSpacing));
            enlargedRectangle = RoiEnlarger.enlarge(crossRoi, (int)Math.round(((nPoints*argoSpacing+2.5)/(crossRoi.getStatistics().roiWidth*pixelSizeImage/2))*crossRoi.getStatistics().roiWidth/2));

        }
        else{
            // for image FoV larger than the pattern FoV => all points
            nPoints = (argoNPoints-1)/2;
            enlargedRectangle = RoiEnlarger.enlarge(crossRoi, (int)Math.round(((nPoints*argoSpacing+1.5)/(crossRoi.getStatistics().roiWidth*pixelSizeImage/2))*crossRoi.getStatistics().roiWidth/2));
        }

        // get the statistics
        double large_rect_roi_x = enlargedRectangle.getStatistics().xCentroid;
        double large_rect_roi_y = enlargedRectangle.getStatistics().yCentroid;
        double large_rect_roi_w = enlargedRectangle.getStatistics().roiWidth;
        double large_rect_roi_h = enlargedRectangle.getStatistics().roiHeight;

        // find ring centers
        ImagePlus imp2 = DoGImage.duplicate();
        // preprocess the image
        IJ.run(imp2, "Median...", "radius="+medianRadius);
        IJ.run(imp2, "Gaussian Blur...", "sigma="+sigma);

        // threshold the image
        IJ.setAutoThreshold(imp2, segMethod+" dark");
        IJ.run(imp2, "Convert to Mask", "");

        // make measurements
        IJ.run("Set Measurements...", "area centroid center display redirect=None decimal=3");
        IJ.run(imp2, "Analyze Particles...", "pixel display clear overlay add");
        ResultsTable rt_points = ResultsTable.getResultsTable("Results");
        RoiManager rm = RoiManager.getRoiManager();
        rt_points.reset();
        rm.runCommand(DoGImage,"Measure");

        // get coordinates of each point
        float[] raw_x_array = rt_points.getColumn(rt_points.getColumnIndex("XM"));
        float[] raw_y_array = rt_points.getColumn(rt_points.getColumnIndex("YM"));
        float[] raw_area_array = rt_points.getColumn(rt_points.getColumnIndex("Area"));

        List<Point2D> gridPoints = new ArrayList<>();

        // filter points according to their position ; keep only those inside the large rectangle and outside the central cross bounding box
        for(int i = 0; i < raw_x_array.length; i++){

            if(Math.abs(raw_x_array[i] - large_rect_roi_x) <= large_rect_roi_w/2 &&
                    Math.abs(raw_y_array[i] - large_rect_roi_y) <= large_rect_roi_h/2 &&
                    !(Math.abs(raw_x_array[i] - crossRoi.getStatistics().xCentroid) <= crossRoi.getStatistics().roiWidth/2 &&
                            Math.abs(raw_y_array[i] - crossRoi.getStatistics().yCentroid) <= crossRoi.getStatistics().roiHeight/2) &&
                    raw_area_array[i] > prtThreshold){
                gridPoints.add(new Point2D.Double(raw_x_array[i], raw_y_array[i]));
            }
        }

        IJ.selectWindow("Results");
        IJ.run("Close");

        return gridPoints;
    }

    /**
     * compute the average step between values of the list
     *
     * The number of points should correspond to a square number - 1, like the below pattern.
     *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * *   * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *
     * @param values
     * @param pixelSize
     * @return
     */
    protected static double getAverageStep(List<Double> values, double pixelSize, int argoSpacing){
        // get min/max values
        Optional<Double> minOpt = values.stream().min(Comparator.naturalOrder());
        Optional<Double> maxOpt = values.stream().max(Comparator.naturalOrder());

        if(!minOpt.isPresent())
            return 0;

        double min = minOpt.get();
        double max = maxOpt.get();

        // compute the raw step
        double step_calc = (max - min) / (int)Math.sqrt(values.size());

        // tolerance on ring center to by-pass potential image rotation
        int tol = (int)(0.6 * argoSpacing / pixelSize);

        // average values on the same line
        List<Double> linesAvgs = new ArrayList<>();
        IntStream.range(0, (int)Math.sqrt(values.size()+1)).forEach(line_idx -> {
            // get all values on the same line
            List<Double> lines = values.stream().filter(e -> ((e > min + line_idx * step_calc - tol) && (e < min + line_idx * step_calc + tol))).collect(Collectors.toList());

            // average all values
            linesAvgs.add(lines.stream().reduce(0.0, Double::sum) / lines.size());
        });

        // compute step between each line
        List<Double> stepAvgs = new ArrayList<>();
        IntStream.range(0, linesAvgs.size()-2).forEach(line_idx -> {
            stepAvgs.add(linesAvgs.get(line_idx+1) - linesAvgs.get(line_idx));
        });

        // compute the average step
        return  stepAvgs.stream().reduce(0.0, Double::sum) / stepAvgs.size();
    }

    /**
     * compute the rotation angle of a point pattern
     *
     * @param values
     * @param crossRoi
     * @return angle in radian
     */
    protected static double getRotationAngle(List<Point2D> values, Roi crossRoi){
        double xCross = crossRoi.getStatistics().xCentroid;
        double yCross = crossRoi.getStatistics().yCentroid;

        // sort all points in natural order of their distance to image center
        List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(xCross,yCross))).collect(Collectors.toList());

        // extract corners
        int lastIdx = values.size()-1;
        List<Point2D> cornerPoints = Arrays.asList(sortedPoints.get(lastIdx), sortedPoints.get(lastIdx - 1), sortedPoints.get(lastIdx - 2), sortedPoints.get(lastIdx - 3));

        // get the top left and top right corners
        List<Point2D> topLeftCornerList = cornerPoints.stream().filter(e->e.getX()<xCross && e.getY()<yCross).collect(Collectors.toList());
        List<Point2D> topRightCornerList = cornerPoints.stream().filter(e->e.getX()>xCross && e.getY()<yCross).collect(Collectors.toList());
        List<Point2D> bottomLeftCornerList = cornerPoints.stream().filter(e->e.getX()<xCross && e.getY()>yCross).collect(Collectors.toList());
        List<Point2D> bottomRightCornerList = cornerPoints.stream().filter(e->e.getX()>xCross && e.getY()>yCross).collect(Collectors.toList());

        Point2D leftCorner;
        Point2D rightCorner;

        if(!topLeftCornerList.isEmpty() && !topRightCornerList.isEmpty()){
            leftCorner = topLeftCornerList.get(0);
            rightCorner = topRightCornerList.get(0);
        }else{
            if(!bottomLeftCornerList.isEmpty() && !bottomRightCornerList.isEmpty()){
                leftCorner = bottomLeftCornerList.get(0);
                rightCorner = bottomRightCornerList.get(0);
            }else{
                IJLogger.error("Compute Rotation angle","At least 2 corners rings are missing in the detection" +
                        "step. Please have a look to the image and increase the exposure time");
                throw new RuntimeException();
            }
        }

        // compute the rotation angle
        double theta = 0;
        if(Math.abs(rightCorner.getX() - leftCorner.getX()) > 0.01){
            theta = Math.atan2(rightCorner.getY() - leftCorner.getY(),rightCorner.getX() - leftCorner.getX());
        }
        return theta;
    }


    /**
     * generate an ideal grid of points, based on the computed spacing between rings and the on the rotation angle
     *
     * @param crossRoi
     * @param nPoints
     * @param xStepAvg
     * @param yStepAvg
     * @param theta
     * @return
     */
    protected static List<Point2D> getIdealGridPoints(Roi crossRoi, int nPoints, double xStepAvg, double yStepAvg, double theta){
        // get central cross position
        double xCross = crossRoi.getStatistics().xCentroid;
        double yCross = crossRoi.getStatistics().yCentroid;

        // compute the affine transform based on the rotation angle
        AffineTransform at = AffineTransform.getRotateInstance(theta, xCross, yCross);

        List<Point2D> idealPoints = new ArrayList<>();

        // define the ideal grid of dots
        IntStream.range(-(nPoints-1)/2, (nPoints-1)/2+1).forEach(yP -> {
            IntStream.range(-(nPoints-1)/2, (nPoints-1)/2+1).forEach(xP -> {
                if (!( xP == 0 && yP == 0) ){ // to avoid making a point at the cross
                    double[] pt = {xCross + xP * xStepAvg, yCross + yP * yStepAvg};
                    at.transform(pt, 0, pt, 0, 1);
                    idealPoints.add(new Point2D.Double(pt[0], pt[1]));
                }
            });
        });

        return idealPoints;
    }


    /**
     * sort a list of ROIs in the same order as the reference list
     *
     * Here we check if "ideal" point from the grid are contained in one of the ROI of the rings,
     * So we have both list in the same order!
     *
     * @param listToSort
     * @param reference
     * @return
     */
    protected static List<Point2D> sortFromReference(List<Roi> listToSort, List<Point2D> reference){
        List<Point2D> sorted = new ArrayList<>();

        // sort the list
        reference.forEach( iPt ->{
            for(Roi gPt : listToSort){
                if (gPt.contains((int)iPt.getX(),(int)iPt.getY())) {
                    ImageStatistics stat = gPt.getStatistics();
                    sorted.add(new Point2D.Double(stat.xCentroid, stat.yCentroid));
                    break;
                }
            }
        });

        return sorted;
    }


    /**
     * compute field distortion metric between an ideal and real set of points in um.
     *
     * @param gridPoints
     * @param idealGridPoints
     * @return
     */
    protected static List<Double> computeFieldDistortion(List<Point2D> gridPoints, List<Point2D> idealGridPoints, double pixelSize){
        // Now we measure distance between ideal point and the measured one
        List<Double> distValues = new ArrayList<>();
        for(int i = 0; i < gridPoints.size(); i++){
            distValues.add(gridPoints.get(i).distance(idealGridPoints.get(i))*pixelSize);
        }

        return distValues;
    }

    /**
     * compute field uniformity metric of points set.
     *
     * @param gridPoints
     * @param imp
     * @param ovalRadius
     * @return
     */
    protected static List<Double> computeFieldUniformity(List<Point2D> gridPoints, ImagePlus imp, double ovalRadius){
        List<Double> intensityValues = new ArrayList<>();
        gridPoints.forEach(pt->{
            // set the ROI & make a circle
            OvalRoi ovalRoi = new OvalRoi(pt.getX()-ovalRadius, pt.getY()-ovalRadius, 2*ovalRadius, 2*ovalRadius);
            imp.setRoi(ovalRoi);
            // compute the mean intensity of the ROI
            intensityValues.add(imp.getStatistics().mean);
        });

        return intensityValues;
    }

    /**
     * compute the Full Width at Half Maximum of a ring in um.
     *
     * @param gridPoints
     * @param imp
     * @param lineLength
     * @return
     */
    protected static List<Double> computeFWHM(List<Point2D> gridPoints, ImagePlus imp, int lineLength, double pixelSize){
        List<Double> fwhmValues = new ArrayList<>();

        double[] xData = new double[lineLength];
        for(int i = 0; i < lineLength; i++) xData[i] = i;//*pixelSize;

        // for each ring
        for(Point2D pt : gridPoints){
            int nAngles = 30;
            double[] fwhmRingValues = new double[nAngles];

            // compute FWHM for different angles
            for(int angle = 0; angle < nAngles; angle++){
                double angleRad = angle * Math.PI / nAngles;
                double[] avgProfile = new double[lineLength];
                for(int i = 0; i < lineLength; i++) {
                    avgProfile[i] = imp.getProcessor().getInterpolatedValue(pt.getX() + i*Math.cos(angleRad), pt.getY() + i*Math.sin(angleRad));
                }

                // DO the curve fitting
                CurveFitter crvFitr = new CurveFitter(xData, avgProfile);
                crvFitr.doFit(CurveFitter.GAUSSIAN);
                double[] params = crvFitr.getParams();

                // Fit parameters as follows: y = a + (b - a) exp( -(x - c^2)/(2*d^2) )
                double d = params[3]; // parameter d of gaussian, related to the FWHM, see http://fr.wikipedia.org/wiki/Largeur_%C3%A0_mi-hauteur
                double fwhm_val = (2 * Math.sqrt(2 * Math.log(2))) * d;

                fwhmRingValues[angle] = fwhm_val*pixelSize;
            }

            // sort and filter FWHM values computed on all the angles for the same ring
            Arrays.sort(fwhmRingValues);
            int q1Pos = (int) (fwhmRingValues.length * 0.25);
            int q3Pos = (int) (fwhmRingValues.length * 0.75);
            double avgFWHM = 0;

            // make the average FWHM
            for(int i = q1Pos; i <= q3Pos; i++)
                avgFWHM += fwhmRingValues[i];

            fwhmValues.add(avgFWHM/(q3Pos-q1Pos+1));
        }

        return fwhmValues;
    }
}
