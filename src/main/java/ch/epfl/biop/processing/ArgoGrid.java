package ch.epfl.biop.processing;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Object to handle multiple characteristics of the detected ArgoSlide grid
 */
public class ArgoGrid {
    private List<Point2D> leftPoints = new ArrayList<>();
    private List<Point2D> rightPoints = new ArrayList<>();
    private List<Point2D> topPoints = new ArrayList<>();
    private List<Point2D> bottomPoints = new ArrayList<>();
    private double rotationAngle = 0;
    private int maxNbPointsPerLine = 0;

    public ArgoGrid(){

    }

    public List<Point2D> getBottomPoints() {
        return bottomPoints;
    }

    public List<Point2D> getLeftPoints() {
        return leftPoints;
    }

    public List<Point2D> getRightPoints() {
        return rightPoints;
    }

    public List<Point2D> getTopPoints() {
        return topPoints;
    }

    public double getRotationAngle() {
        return rotationAngle;
    }

    public int getMaxNbPointsPerLine() {
        return maxNbPointsPerLine;
    }

    public void setBottomPoints(List<Point2D> bottomPoints) {
        this.bottomPoints = bottomPoints;
    }

    public void setLeftPoints(List<Point2D> leftPoints) {
        this.leftPoints = leftPoints;
    }

    public void setRightPoints(List<Point2D> rightPoints) {
        this.rightPoints = rightPoints;
    }

    public void setTopPoints(List<Point2D> topPoints) {
        this.topPoints = topPoints;
    }

    public void setRotationAngle(double rotationAngle) {
        this.rotationAngle = rotationAngle;
    }

    public void setMaxNbPointsPerLine(int maxNbPointsPerLine) {
        this.maxNbPointsPerLine = maxNbPointsPerLine;
    }
}
