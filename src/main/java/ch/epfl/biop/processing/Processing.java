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

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
     * @param argoSlide The name of the ArgoSlide selected in the GUI
     * @param argoSpacing distance between two rings in the grid
     * @param argoFOV FoV of the pattern B of the ArgoSlide
     * @param argoNPoints number of rings in the same line
     */
    public static void run(Retriever retriever, boolean savingHeatMaps, Sender sender, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius, String argoSlide, int argoSpacing, int argoFOV, int argoNPoints){
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
                                userParticleThreshold, userRingRadius, argoSlide, argoSpacing, argoFOV, argoNPoints);
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
                if(isOldProtocol || !imageFile.getImagedFoV().equals(Tools.PARTIAL_FOV)) sender.sendHeatMaps(channel.getFieldDistortionHeatMap(imageFile.getImgNameWithoutExtension()));
                if(isOldProtocol || !imageFile.getImagedFoV().equals(Tools.PARTIAL_FOV)) sender.sendHeatMaps(channel.getFieldUniformityHeatMap(imageFile.getImgNameWithoutExtension()));
                if(isOldProtocol || !imageFile.getImagedFoV().equals(Tools.FULL_FOV)) sender.sendHeatMaps(channel.getFWHMHeatMap(imageFile.getImgNameWithoutExtension()));
            }
        }

        // send results table
        if(isOldProtocol || !imageFile.getImagedFoV().equals(Tools.PARTIAL_FOV)) sender.sendResultsTable(distortionValues, chIds, false, "Field_distortion");
        if(isOldProtocol || !imageFile.getImagedFoV().equals(Tools.PARTIAL_FOV)) sender.sendResultsTable(uniformityValues, chIds, false, "Field_uniformity");
        if(isOldProtocol || !imageFile.getImagedFoV().equals(Tools.FULL_FOV)) sender.sendResultsTable(fwhmValues, chIds, false, "FWHM");

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
     * @param imp
     * @param crossRoi
     * @param medianRadius
     * @param overRadius
     * @param prtThreshold
     * @param sigma
     * @param segMethod
     * @return
     */
    protected static List<Point2D> getGridPoint(ImagePlus imp, Roi crossRoi, double sigma, double medianRadius,
                                                double prtThreshold, String segMethod, int overRadius){

        // get the statistics
        Roi enlargedRectangle = new Roi(new Rectangle(overRadius, overRadius, imp.getWidth()-2*overRadius, imp.getHeight()-2*overRadius ));

        // find ring centers
        ImagePlus imp2 = imp.duplicate();
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
        rm.runCommand(imp,"Measure");

        // get coordinates of each point
        float[] raw_x_array = rt_points.getColumn(rt_points.getColumnIndex("XM"));
        float[] raw_y_array = rt_points.getColumn(rt_points.getColumnIndex("YM"));
        float[] raw_area_array = rt_points.getColumn(rt_points.getColumnIndex("Area"));

        List<Point2D> gridPoints = new ArrayList<>();

        // filter points according to their position ; keep only those inside the large rectangle and outside the central cross bounding box
        for(int i = 0; i < raw_x_array.length; i++){

            if(enlargedRectangle.containsPoint(raw_x_array[i], raw_y_array[i]) &&
                    !crossRoi.containsPoint(raw_x_array[i], raw_y_array[i]) &&
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
     * @param xCross
     * @param yCross
     * @return angle in radian
     */
    protected static double getRotationAngle(List<Point2D> values, double xCross, double yCross, double pixelSize,
                                             int argoSpacing, double ovalRadius, ImagePlus imp){

        Point2D.Double vectToLeft = new Point2D.Double(-argoSpacing/pixelSize, 0);
        double initX = xCross;
        double initY = yCross;

        // find all points along the central horizontal line to the left of the cross
        List<Point2D> lineLeft = new ArrayList<>();
        Point2D.Double theoPoint;
        do{
            theoPoint = new Point2D.Double(initX + vectToLeft.getX(), initY + vectToLeft.getY());
            if(theoPoint.getX() > ovalRadius){
                Point2D.Double finalTheoPoint = theoPoint;
                List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(finalTheoPoint.getX(),finalTheoPoint.getY()))).collect(Collectors.toList());
                Point2D closerRing = sortedPoints.get(0);
                lineLeft.add(closerRing);

                vectToLeft = new Point2D.Double(closerRing.getX() - initX, closerRing.getY() - initY);

                initX = closerRing.getX();
                initY = closerRing.getY();
            }
        }while (theoPoint.getX() > ovalRadius);


        // find all points along the central horizontal line to the right of the cross
        Point2D.Double vectToRight = new Point2D.Double(argoSpacing/pixelSize, 0);
        initX = xCross;
        initY = yCross;
        List<Point2D> lineRight = new ArrayList<>();

        do{
            theoPoint = new Point2D.Double(initX + vectToRight.getX(), initY + vectToRight.getY());
            if(theoPoint.getX() < imp.getWidth() - ovalRadius){
                Point2D.Double finalTheoPoint = theoPoint;
                List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(finalTheoPoint.getX(),finalTheoPoint.getY()))).collect(Collectors.toList());
                Point2D closerRing = sortedPoints.get(0);
                lineRight.add(closerRing);

                vectToRight = new Point2D.Double(closerRing.getX() - initX, closerRing.getY() - initY);

                initX = closerRing.getX();
                initY = closerRing.getY();
            }
        }while (theoPoint.getX() < imp.getWidth() - ovalRadius);

        // find all points along the central vertical line to the bottom of the cross
        Point2D.Double vectToBottom = new Point2D.Double(0, argoSpacing/pixelSize);
        initX = xCross;
        initY = yCross;
        List<Point2D> lineBottom = new ArrayList<>();

        do{
            theoPoint = new Point2D.Double(initX + vectToBottom.getX(), initY + vectToBottom.getY());
            if(theoPoint.getY() < imp.getHeight() - ovalRadius){
                Point2D.Double finalTheoPoint = theoPoint;
                List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(finalTheoPoint.getX(),finalTheoPoint.getY()))).collect(Collectors.toList());
                Point2D closerRing = sortedPoints.get(0);
                lineBottom.add(closerRing);

                vectToBottom = new Point2D.Double(closerRing.getX() - initX, closerRing.getY() - initY);

                initX = closerRing.getX();
                initY = closerRing.getY();
            }
        }while (theoPoint.getY() < imp.getHeight() - ovalRadius);


        // find all points along the central vertical line to the top of the cross
        Point2D.Double vectToTop = new Point2D.Double(0, -argoSpacing/pixelSize);
        initX = xCross;
        initY = yCross;
        List<Point2D> lineTop = new ArrayList<>();

        do{
            theoPoint = new Point2D.Double(initX + vectToTop.getX(), initY + vectToTop.getY());
            if(theoPoint.getY() > ovalRadius){
                Point2D.Double finalTheoPoint = theoPoint;
                List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(finalTheoPoint.getX(),finalTheoPoint.getY()))).collect(Collectors.toList());
                Point2D closerRing = sortedPoints.get(0);
                lineTop.add(closerRing);

                vectToTop = new Point2D.Double(closerRing.getX() - initX, closerRing.getY() - initY);

                initX = closerRing.getX();
                initY = closerRing.getY();
            }
        }while (theoPoint.getY() > ovalRadius);



        if(!lineLeft.isEmpty() && !lineRight.isEmpty() && !lineTop.isEmpty() && !lineBottom.isEmpty()) {

            Collections.reverse(lineLeft);
            lineLeft.addAll(lineRight);

            Collections.reverse(lineTop);
            lineTop.addAll(lineBottom);

            double[] xArrayH = lineLeft.stream().mapToDouble(Point2D::getX).toArray();
            double[] yArrayH = lineLeft.stream().mapToDouble(Point2D::getY).toArray();
            double[] xArrayV = lineTop.stream().mapToDouble(Point2D::getX).toArray();
            double[] yArrayV = lineTop.stream().mapToDouble(Point2D::getY).toArray();

            // do a curve fitting of type : y = a*x + b
            CurveFitter fitter = new CurveFitter(xArrayH, yArrayH);
            fitter.doFit(CurveFitter.STRAIGHT_LINE);
            double a = fitter.getParams()[1];
            double angle1 = Math.atan2(a, 1);

            // do a curve fitting of type : y = a*x + b
            CurveFitter fitter2 = new CurveFitter(yArrayV, xArrayV);
            fitter2.doFit(CurveFitter.STRAIGHT_LINE);
            a = fitter2.getParams()[1];
            double angle2 = Math.atan2(a, 1);

            return (angle1 + angle2)/2;

        }
        return Double.NaN;

        /*// sort all points in natural order of their distance to image center
        List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(xCross,yCross))).collect(Collectors.toList());

        // extract corners
        int lastIdx = values.size()-1;
        List<Point2D> cornerPoints = Arrays.asList(sortedPoints.get(lastIdx), sortedPoints.get(lastIdx - 1), sortedPoints.get(lastIdx - 2), sortedPoints.get(lastIdx - 3));

        // get the top left and top right corners
        List<Point2D> topLeftCornerList = cornerPoints.stream().filter(e->e.getX()<xCross && e.getY()<yCross).collect(Collectors.toList());
        List<Point2D> topRightCornerList = cornerPoints.stream().filter(e->e.getX()>xCross && e.getY()<yCross).collect(Collectors.toList());
        List<Point2D> bottomLeftCornerList = cornerPoints.stream().filter(e->e.getX()<xCross && e.getY()>yCross).collect(Collectors.toList());
        List<Point2D> bottomRightCornerList = cornerPoints.stream().filter(e->e.getX()>xCross && e.getY()>yCross).collect(Collectors.toList());

        // force to have the 4 corners
        if(!topLeftCornerList.isEmpty() && !topRightCornerList.isEmpty() && !bottomLeftCornerList.isEmpty() && !bottomRightCornerList.isEmpty()){
            // get the points
            Point2D topLeftCorner = topLeftCornerList.get(0);
            Point2D topRightCorner = topRightCornerList.get(0);
            Point2D bottomLeftCorner = bottomLeftCornerList.get(0);
            Point2D bottomRightCorner = bottomRightCornerList.get(0);

            // compute vector of the quadrilateral shape formed by the 4 corners
            Point2D vectTLTR = new Point2D.Double(topLeftCorner.getX() - topRightCorner.getX(), topLeftCorner.getY() - topRightCorner.getY());
            Point2D vectTLBL = new Point2D.Double(topLeftCorner.getX() - bottomLeftCorner.getX(), topLeftCorner.getY() - bottomLeftCorner.getY());
            Point2D vectTRBR = new Point2D.Double(topRightCorner.getX() - bottomRightCorner.getX(), topRightCorner.getY() - bottomRightCorner.getY());
            Point2D vectBLBR = new Point2D.Double(bottomLeftCorner.getX() - bottomRightCorner.getX(), bottomLeftCorner.getY() - bottomRightCorner.getY());

            // compute the dot product
            double dotProduct1 = (vectTLBL.getX() * vectTLTR.getX()) +(vectTLBL.getY() * vectTLTR.getY());
            double dotProduct2 = (vectTLTR.getX() * vectTRBR.getX()) +(vectTLTR.getY() * vectTRBR.getY());
            double dotProduct3 = (vectTRBR.getX() * vectBLBR.getX()) +(vectTRBR.getY() * vectBLBR.getY());
            double dotProduct4 = (vectTLBL.getX() * vectBLBR.getX()) +(vectTLBL.getY() * vectBLBR.getY());

            // compute the inner angles of the quadrilateral shape
            double angle1 = Math.acos((dotProduct1) / (topRightCorner.distance(topLeftCorner) * topLeftCorner.distance(bottomLeftCorner)));
            double angle2 = Math.acos((dotProduct2) / (topRightCorner.distance(topLeftCorner) * topRightCorner.distance(bottomRightCorner)));
            double angle3 = Math.acos((dotProduct3) / (topRightCorner.distance(bottomRightCorner) * bottomLeftCorner.distance(bottomRightCorner)));
            double angle4 = Math.acos((dotProduct4) / (topLeftCorner.distance(bottomLeftCorner) * bottomLeftCorner.distance(bottomRightCorner)));

            double upperBound = 93 * Math.PI / 180;
            double lowerBound = 87 * Math.PI / 180;

            // only compute the pattern rotation angle IF the quadrilateral shape is a rectangle (i.e. angles at 90°)
            if(angle1 > lowerBound && angle1 < upperBound && angle2 > lowerBound && angle2 < upperBound &&
                angle3 > lowerBound && angle3 < upperBound && angle4 > lowerBound && angle4 < upperBound){

                IJLogger.info("Rotation angle", "Corner rings are correctly detected" );

                // compute the rotation angle
                double theta = 0;
                if(Math.abs(topRightCorner.getX() - topLeftCorner.getX()) > 0.01){
                    theta = Math.atan2(topRightCorner.getY() - topLeftCorner.getY(),topRightCorner.getX() - topLeftCorner.getX());
                }

                return theta;
            }else{
                IJLogger.error("Rotation angle", "Corner rings are not correctly detected as they do not form a rectangle : " +
                        (angle1*180/Math.PI)+"°, "+(angle2*180/Math.PI)+"°, "+(angle3*180/Math.PI)+"°, "+(angle4*180/Math.PI)+". " +
                        "Angles should be between "+(lowerBound*180/Math.PI)+" and "+(upperBound*180/Math.PI));
                return Double.NaN;
            }

        }else{
            IJLogger.error("Rotation angle","At least one corner is missing ; The rotation angle cannot be determined");
            return Double.NaN;
        }*/
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
