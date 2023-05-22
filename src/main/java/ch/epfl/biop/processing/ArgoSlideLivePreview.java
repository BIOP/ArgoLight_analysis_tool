package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.IJLogger;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ArgoSlideLivePreview {

    final private static int argoSpacing = 15; // um
    final private static int argoFOV = 570; // um
    final private static int argoNPoints = 39; // on each row/column
    //private static

    /**
     * Run the analysis on the current image.
     *
     * @param imp image to process
     * @param userSigma value of sigma for gaussian blurring
     * @param userMedianRadius value of median radius for median filtering
     * @param userThresholdingMethod thresholding method used
     * @param userParticleThreshold value of the threshold on particle size
     * @param userRingRadius value of the analysis circle radius around each ring
     */
    public static void run(ImagePlus imp, double pixelSizeImage, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius) {

        // pixel size of the image
        //final double pixelSizeImage = imp.getCalibration().pixelWidth;
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
        List<Point2D> gridPoints = Processing.getGridPoint(imp, crossRoi, pixelSizeImage, sigma, medianRadius,
                particleThreshold, userThresholdingMethod, argoFOV, argoSpacing, argoNPoints);

        // reduced grid to compute average step
        List<Point2D> smallerGrid = gridPoints.stream()
                .filter(e -> (Math.abs(e.getX() - crossRoi.getStatistics().xCentroid) < (2.5 * argoSpacing) / pixelSizeImage && Math.abs(e.getY() - crossRoi.getStatistics().yCentroid) < (2.5 * argoSpacing) / pixelSizeImage))
                .collect(Collectors.toList());

        // get the average x step
        double xStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage, argoSpacing);

        // get the average y step
        double yStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage, argoSpacing);

        // get the rotation angle
        double rotationAngle = Processing.getRotationAngle(gridPoints, crossRoi);

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
        for(Point2D pR : idealGridPoints) {
            OvalRoi roi = new OvalRoi(pR.getX() - ovalRadius / 2 + 0.5, pR.getY() - ovalRadius / 2 + 0.5, ovalRadius, ovalRadius);
            roi.setStrokeColor(Color.GREEN);
            idealGridPointsRoi.add(roi);
        }

        Overlay overlay = new Overlay();
        idealGridPointsRoi.forEach(overlay::add);
        gridPointRois.forEach(overlay::add);
        overlay.add(crossRoi);
        imp.setOverlay(overlay);
    }
}
