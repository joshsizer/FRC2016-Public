package edu.wpi.first.smartdashboard.gui.elements;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_core.CvSize;
import org.bytedeco.javacpp.opencv_core.IplConvKernel;
import org.bytedeco.javacpp.opencv_core.IplImage;
import org.bytedeco.javacpp.opencv_imgproc;
import edu.wpi.first.smartdashboard.robot.Robot;
import edu.wpi.first.smartdashboard.camera.WPICameraExtension;
import edu.wpi.first.smartdashboard.properties.Property;
import edu.wpi.first.wpijavacv.*;
import edu.wpi.first.smartdashboard.properties.DoubleProperty;
import edu.wpi.first.smartdashboard.properties.IntegerProperty;

/* HOW TO GET THIS COMPILING IN NETBEANS:

 *  1. Install the SmartDashboard using the installer (if on Windows)
 *      1a. Verify that the OpenCV libraries are in your PATH (on Windows)
 *  2. Add the following libraries to the project:
 *     SmartDashboard.jar
 *     extensions/WPICameraExtension.jar
 *     lib/NetworkTable_Client.jar
 *     extensions/lib/javacpp.jar
 *     extensions/lib/javacv-*your environment*.jar
 *     extensions/lib/javacv.jar
 *     extensions/lib/WPIJavaCV.jar
 *
 */
/**
 *
 * @author jrussell
 */
public class DaisyCV extends WPICameraExtension {
	private static final long serialVersionUID = 1870790236426202916L;
	public static final String NAME = "DaisyCV 2016";
	private WPIColor targetColor = new WPIColor(0, 255, 0);
	//private NetworkTable SmartDashboard;
	//private PropertySet properties;

	// Constants that need to be tuned
	// basically, the horizontal slope is the acceptable tilt of the lines found
	private static final double kNearlyHorizontalSlope = Math.tan(Math.toRadians(20));
	private static final double kNearlyVerticalSlope = Math.tan(Math.toRadians(90 - 20));
	private static final int kMinWidth = 20;
	private static final int kMaxWidth = 200;
	private static final double kRangeRPMOffset = 0.0;
	private static final double kRangeOffset = 10.0;
	private static final int kHoleClosingIterations = 2;

	//private static double pShooterOffsetDeg = 1.0;
	private static final double kHorizontalFOVDeg = 47.0;

	private static final double kVerticalFOVDeg = 480.0 / 640.0 * kHorizontalFOVDeg;
	private static final double kCameraHeightIn = 38.5;
	private static final double kCameraPitchDeg = 18.8;
	private static final double kTargetHeightIn = 82.0 + 13; // 82 to bottom of target, 13 to middle to bottom of target
	// p for properties, so that editing the program can be done on the fly
	// they are initialized to default values, and these values are used if 
	// a properties file cannot be found
	private IntegerProperty pHueLowerBound = new IntegerProperty(this, "Hue low", 53);
	private IntegerProperty pHueUpperBound = new IntegerProperty(this, "Hue high", 255);
	private IntegerProperty pSaturationLowerBound = new IntegerProperty(this, "Saturation low", 202);
	private IntegerProperty pSaturationUpperBound = new IntegerProperty(this, "Saturation high", 255);
	private IntegerProperty pValueLowerBound = new IntegerProperty(this, "Value low", 180); 
	private IntegerProperty pValueUpperBound = new IntegerProperty(this, "Value high", 255);
	private DoubleProperty pShooterOffsetDeg = new DoubleProperty(this, "Horizontal Offset Degrees", 0.0);
																
	private TreeMap<Double, Double> rangeTable;

	private boolean m_debugMode = false;

	// Store JavaCV temporaries as members to reduce memory management during
	// processing
	private CvSize size = null;
	private WPIContour[] contours;
	private ArrayList<WPIPolygon> polygons;
	private IplConvKernel morphKernel;
	private IplImage bin;
	private IplImage hsv;
	private IplImage hue;
	private IplImage sat;
	private IplImage val;
	private WPIPoint linePt1;
	private WPIPoint linePt2;
	private int horizontalOffsetPixels;

	public DaisyCV() {
		this(false);
	}

	public DaisyCV(boolean debug) {
		m_debugMode = debug;
		morphKernel = IplConvKernel.create(3, 3, 1, 1, opencv_imgproc.CV_SHAPE_RECT, null);
		
		rangeTable = new TreeMap<Double, Double>();
		rangeTable.put(150.0, 6375.0 + kRangeRPMOffset);
		rangeTable.put(141.0, 6250.0 + kRangeRPMOffset);
		
		DaisyExtensions.init();
	}

	public double getRPMsForRange(double range) {
		double lowKey = -1.0;
		double lowVal = -1.0;
		for (double key : rangeTable.keySet()) {
			if (range < key) {
				double highVal = rangeTable.get(key);
				if (lowKey > 0.0) {
					double m = (range - lowKey) / (key - lowKey);
					return lowVal + m * (highVal - lowVal);
				} else
					return highVal;
			}
			lowKey = key;
			lowVal = rangeTable.get(key);
		}

		return 4000 + kRangeRPMOffset;
	}
	
	@Override
	public WPIImage processImage(WPIColorImage rawImage) {
		double heading = 0.0;

		// Get the current heading of the robot first
		if (!m_debugMode) {
			try { heading = Robot.getTable().getNumber("heading", 0.0); }
			catch (NoSuchElementException | IllegalArgumentException e) { }
		}

		if (size == null || size.width() != rawImage.getWidth() || size.height() != rawImage.getHeight()) {
			size = opencv_core.cvSize(rawImage.getWidth(), rawImage.getHeight());
			bin = IplImage.create(size, 8, 1);
			hsv = IplImage.create(size, 8, 3);
			hue = IplImage.create(size, 8, 1);
			sat = IplImage.create(size, 8, 1);
			val = IplImage.create(size, 8, 1);
			horizontalOffsetPixels = (int) Math.round(pShooterOffsetDeg.getValue() * (size.width() / kHorizontalFOVDeg));
			// the points for the green line burned into the image
			linePt1 = new WPIPoint(size.width() / 2, size.height() - 1);
			linePt2 = new WPIPoint(size.width() / 2, 0);
		}
		// Get the raw IplImages for OpenCV
		IplImage input = DaisyExtensions.getIplImage(rawImage);

		// Convert to HSV color space
		opencv_imgproc.cvCvtColor(input, hsv, opencv_imgproc.CV_BGR2HSV);
		opencv_core.cvSplit(hsv, hue, sat, val, null);

		// Threshold each component separately
		// Hue
		// NOTE: Red is at the end of the color space, so you need to OR together
		// a thresh and inverted thresh in order to get points that are red
		opencv_imgproc.cvThreshold(hue, bin, pHueLowerBound.getValue().intValue(), 
				pHueUpperBound.getValue().intValue(), opencv_imgproc.CV_THRESH_BINARY);
		opencv_imgproc.cvThreshold(hue, hue, 60 + 15, 255, opencv_imgproc.CV_THRESH_BINARY_INV);

		// Saturation
		opencv_imgproc.cvThreshold(sat, sat, pSaturationLowerBound.getValue().intValue(), 
				pSaturationUpperBound.getValue().intValue(), opencv_imgproc.CV_THRESH_BINARY);

		// Value
		opencv_imgproc.cvThreshold(val, val, pValueLowerBound.getValue().intValue(), 
				pValueUpperBound.getValue().intValue(), opencv_imgproc.CV_THRESH_BINARY);

		// Combine the results to obtain our binary image which should for the
		// most
		// part only contain pixels that we care about
		opencv_core.cvAnd(bin, hue, bin, null);
		opencv_core.cvAnd(bin, sat, bin, null);
		opencv_core.cvAnd(bin, val, bin, null);

		// Uncomment the next two lines to see the raw binary image
		// CanvasFrame result = new CanvasFrame("binary");
		// result.showImage(WPIImage.convertIplImageToBuffImage(bin));

		// Fill in any gaps using binary morphology
		opencv_imgproc.cvMorphologyEx(bin, bin, null, morphKernel, opencv_imgproc.CV_MOP_CLOSE, kHoleClosingIterations);

		// Uncomment the next two lines to see the image post-morphology
		// CanvasFrame result2 = new CanvasFrame("morph");
		// result2.showImage(WPIImage.convertIplImageToBuffImage(bin));

		// Find contours
		WPIBinaryImage binWpi = DaisyExtensions.makeWPIBinaryImage(bin);
		contours = DaisyExtensions.findConvexContours(binWpi);

		polygons = new ArrayList<WPIPolygon>();
		for (WPIContour c : contours) {
			double ratio = ((double) c.getHeight()) / ((double) c.getWidth());
			if (ratio < 1.0 && ratio > 0.5 && c.getWidth() > kMinWidth && c.getWidth() < kMaxWidth) {
				polygons.add(c.approxPolygon(20));
			}
		}

		WPIPolygon square = null;
		int largest = Integer.MIN_VALUE;

		for (WPIPolygon p : polygons) {
			if (p.isConvex() && p.getNumVertices() == 4) {
				// We passed the first test...we fit a rectangle to the polygon
				// Now do some more tests

				WPIPoint[] points = p.getPoints();
				// We expect to see a top line that is nearly horizontal, and
				// two side lines that are nearly vertical
				int numNearlyHorizontal = 0;
				int numNearlyVertical = 0;
				for (int i = 0; i < 4; i++) {
					double dy = points[i].getY() - points[(i + 1) % 4].getY();
					double dx = points[i].getX() - points[(i + 1) % 4].getX();
					double slope = Double.MAX_VALUE;
					if (dx != 0)
						slope = Math.abs(dy / dx);

					if (slope < kNearlyHorizontalSlope)
						++numNearlyHorizontal;
					else if (slope > kNearlyVerticalSlope)
						++numNearlyVertical;
				}
				
				if (numNearlyHorizontal >= 1 && numNearlyVertical == 2) {
					rawImage.drawPolygon(p, WPIColor.BLUE, 2);

					int pCenterX = (p.getX() + (p.getWidth() / 2));
					int pCenterY = (p.getY());
					int pArea = (p.getArea());

					rawImage.drawPoint(new WPIPoint(pCenterX, pCenterY), WPIColor.RED, 5);
					// because coord system is funny( largest y value is at
					// bottom of picture)

					if (pArea > largest) {
						square = p;
						largest = pArea;
					}
				}
			} else {
				rawImage.drawPolygon(p, WPIColor.YELLOW, 1);
			}
		}
		
		if (square != null) {
			double x = square.getX() + (square.getWidth() / 2);
			x = (2 * (x / size.width())) - 1;

			double y = square.getY();
			y = -((2 * (y / size.height())) - 1);

			double azimuth = this.boundAngle0to360Degrees(x * kHorizontalFOVDeg / 2.0 + heading - pShooterOffsetDeg.getValue());
			double range = ((kTargetHeightIn - kCameraHeightIn)
					/ Math.tan((y * kVerticalFOVDeg / 2.0 + kCameraPitchDeg) * Math.PI / 180.0) - kRangeOffset);
			double rpms = getRPMsForRange(range);

			if (!m_debugMode) {
				Robot.getTable().putBoolean("found", true);
				Robot.getTable().putNumber("azimuth", azimuth);
				Robot.getTable().putNumber("rpms", rpms);
				Robot.getTable().putNumber("range", range);
			} else {
				System.out.println("Target found");
				System.out.println("x: " + x);
				System.out.println("y: " + y);
				System.out.println("azimuth: " + azimuth);
				System.out.println("range: " + range);
				System.out.println("rpms: " + rpms);
			}
			
			
			
			rawImage.drawPolygon(square, targetColor, 7);
		} else {

			if (!m_debugMode) {
				Robot.getTable().putBoolean("found", false);
			} else {
				System.out.println("Target not found");
			}
		}
		boolean readyToShoot = false;
		if (!m_debugMode) 
			readyToShoot = Robot.getTable().getBoolean("ReadyToShoot", false);
			
		IplImage image = rawImage.getIplImage();
		if (readyToShoot)
			this.drawGreenPolygon(image);
		else
			this.drawRedPolygon(image);
		
		// Draw a crosshair
		rawImage.drawLine(linePt1, linePt2, targetColor, 2);
		DaisyExtensions.releaseMemory();
	
		return rawImage;
	}

	private double boundAngle0to360Degrees(double angle) {
		// Naive algorithm
		while (angle >= 360.0) {
			angle -= 360.0;
		}
		while (angle < 0.0) {
			angle += 360.0;
		}
		return angle;
	}

	@Override
	public void propertyChanged(Property arg0) {
		super.propertyChanged(arg0);
	}

	@Override
	public void init() {
		super.init();
		/*
		if (Desktop.isDesktopSupported()) {
			try {
				Desktop.getDesktop().browse(new URI("http://" + super.ipProperty.getValue()));
			} catch (IOException | URISyntaxException e) {
				System.err.println("Could not open camera webpage.");
			}
		} */
	}
	
	public void drawGreenPolygon(IplImage image) {
	    	opencv_imgproc.rectangle(opencv_core.cvarrToMat(image),
	        		new opencv_core.Point(0,0), new opencv_core.Point(50,50), new opencv_core.Scalar(0, 255, 0, 0), 50, 8, 0);
    }
    public void drawRedPolygon(IplImage image) {
    	opencv_core.Point pt1 = new opencv_core.Point(image.width() - 50, 1);
    	opencv_core.Point pt2 = new opencv_core.Point(image.width(), 50);
        opencv_imgproc.rectangle(opencv_core.cvarrToMat(image),
        		pt1, pt2, new opencv_core.Scalar(0, 0, 255, 0), 50, 0, 0);
    }
}
