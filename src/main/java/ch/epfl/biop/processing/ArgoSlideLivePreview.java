package ch.epfl.biop.processing;

import ch.epfl.biop.utils.IJLogger;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ArgoSlideLivePreview {

    private static double xStepAvg = -1;
    private static double yStepAvg = -1;
    private static double rotationAngle = 10;

    /**
     * Run the analysis on the current image.
     *
     * @param imp image to process
     * @param userSigma value of sigma for gaussian blurring
     * @param userMedianRadius value of median radius for median filtering
     * @param userThresholdingMethod thresholding method used
     * @param userParticleThreshold value of the threshold on particle size
     * @param userRingRadius value of the analysis circle radius around each ring
     * @param argoSpacing distance between two rings in the grid
     * @param argoFOV FoV of the pattern B of the ArgoSlide
     * @param argoNPoints number of rings in the same line
     */
    public static void run(ImagePlus imp, double pixelSizeImage, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius, int argoSpacing, int argoFOV, int argoNPoints) {

        // spot radius to compute the FWHM
        final int lineLength = (int) (userRingRadius / pixelSizeImage);
        // spot radius to compute other metrics
        final int ovalRadius = lineLength;
        // sigma for gaussian blurring
        final double sigma = userSigma / pixelSizeImage;
        // median radius for median filtering
        final double medianRadius = userMedianRadius / pixelSizeImage;
        // threshold on the size of particles to filter
        final double particleThreshold = userParticleThreshold / pixelSizeImage;

        IJ.run(imp,"Remove Overlay", "");

        RoiManager roiManager = RoiManager.getInstance();
        if(roiManager == null)  roiManager = new RoiManager();
        roiManager.reset();

        // get the central cross
        Roi crossRoi = Processing.getCentralCross(imp, roiManager, pixelSizeImage, userThresholdingMethod, argoFOV);

        roiManager.reset();
        ImageStatistics crossStata = crossRoi.getStatistics();
        double xCross = crossStata.xCentroid;
        double yCross = crossStata.yCentroid;

        List<Point2D> gridPoints = Processing.getGridPoint(imp, crossRoi,  sigma, medianRadius,
                particleThreshold, userThresholdingMethod, ovalRadius);

        if(gridPoints.isEmpty()){
            IJLogger.error("Ring detection", "No rings are detected on the current image. " +
                    "Cannot compute metrics");
            rotationAngle = 10;
            xStepAvg = -1;
            yStepAvg = -1;
            imp.setOverlay(null);
            return;
        }

        // reduced grid to compute average step
        List<Point2D> smallerGrid = gridPoints.stream()
                .filter(e -> (Math.abs(e.getX() - xCross) < (2.5 * argoSpacing) / pixelSizeImage && Math.abs(e.getY() - yCross) < (2.5 * argoSpacing) / pixelSizeImage))
                .collect(Collectors.toList());

        // get the average x step
        xStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage, argoSpacing);

        // get the average y step
        yStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage, argoSpacing);

        // get the rotation angle
        try {
            // get the rotation angle
            rotationAngle = Processing.getRotationAngle(gridPoints, xCross, yCross, pixelSizeImage, argoSpacing, ovalRadius, imp);
            if(Double.isNaN(rotationAngle)){
                IJLogger.warn("Your image is not properly centered. Try with image center as reference center...");
               /* rotationAngle = Processing.getRotationAngle(gridPoints, (double) imp.getWidth() / 2, (double) imp.getHeight() / 2);
                if(Double.isNaN(rotationAngle)){
                    IJLogger.error("Compute Rotation angle","At least 2 corners rings are missing in the detection" +
                            "step. Please have a look to the image and increase the exposure time");
                    rotationAngle = 10;
                }*/
            }
        }catch(Exception e){

        }
        // create grid point ROIs
        List<Roi> gridPointRois = new ArrayList<>();
        for(Point2D pR : gridPoints)
            gridPointRois.add(new OvalRoi((pR.getX() - 4 * ovalRadius + 0.5), pR.getY() - 4 * ovalRadius + 0.5, 8 * ovalRadius, 8 * ovalRadius));

        // get the ideal grid
        List<Point2D> idealGridPoints = Processing.getIdealGridPoints(crossRoi, (int) Math.sqrt(gridPoints.size() + 1), xStepAvg, yStepAvg, rotationAngle);

        // sort the computed grid points according to ideal grid order
        gridPoints = Processing.sortFromReference(gridPointRois, idealGridPoints);
        gridPointRois = new ArrayList<>();

        for(Point2D pR : gridPoints) {
            OvalRoi roi = new OvalRoi((pR.getX() - ovalRadius + 0.5), pR.getY() - ovalRadius + 0.5, 2 * ovalRadius, 2 * ovalRadius);
            roi.setStrokeColor(Color.RED);
            gridPointRois.add(roi);
        }

        List<Roi> idealGridPointsRoi = new ArrayList<>();
        double idealSize = 0.4/pixelSizeImage;
        for(Point2D pR : idealGridPoints) {
            OvalRoi roi = new OvalRoi(pR.getX() - idealSize + 0.5, pR.getY() - idealSize + 0.5, 2*idealSize, 2*idealSize);
            roi.setStrokeColor(Color.GREEN);
            roi.setFillColor(Color.GREEN);
            idealGridPointsRoi.add(roi);
        }

        Overlay overlay = new Overlay();
        idealGridPointsRoi.forEach(overlay::add);
        gridPointRois.forEach(overlay::add);
        overlay.add(crossRoi);
        imp.setOverlay(overlay);
    }

    public static double getXAvgStep(){
        return xStepAvg;
    }

    public static double getYAvgStep(){
        return yStepAvg;
    }

    public static double getRotationAngle(){
        if(rotationAngle > 2*Math.PI)
            return Double.NaN;
        return rotationAngle * 180 / Math.PI;
    }
}
