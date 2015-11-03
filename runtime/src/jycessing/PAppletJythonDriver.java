/*
 * Copyright 2010 Jonathan Feinberg
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package jycessing;

import java.awt.Component;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jycessing.IOUtil.ResourceReader;
import jycessing.mode.run.WrappedPrintStream;
import jycessing.mode.run.WrappedPrintStream.PushedOut;

import org.python.core.CompileMode;
import org.python.core.CompilerFlags;
import org.python.core.Py;
import org.python.core.PyBaseCode;
import org.python.core.PyBoolean;
import org.python.core.PyCode;
import org.python.core.PyException;
import org.python.core.PyFloat;
import org.python.core.PyFunction;
import org.python.core.PyIndentationError;
import org.python.core.PyInteger;
import org.python.core.PyJavaType;
import org.python.core.PyObject;
import org.python.core.PySet;
import org.python.core.PyString;
import org.python.core.PyStringMap;
import org.python.core.PySyntaxError;
import org.python.core.PyTuple;
import org.python.core.PyType;
import org.python.core.PyUnicode;
import org.python.util.InteractiveConsole;

import processing.awt.PSurfaceAWT;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;
import processing.core.PSurface;
import processing.event.KeyEvent;
import processing.event.MouseEvent;
import processing.opengl.PShader;
import processing.opengl.PSurfaceJOGL;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.jogamp.newt.opengl.GLWindow;

/**
 *
 * @author Jonathan Feinberg &lt;jdf@pobox.com&gt;
 *
 */
@SuppressWarnings("serial")
public class PAppletJythonDriver extends PApplet {

  public static final String C_LIKE_LOGICAL_AND_ERROR_MESSAGE =
      "Did you maybe use \"&&\" instead of \"and\"?";
  public static final String C_LIKE_LOGICAL_OR_ERROR_MESSAGE =
      "Did you maybe use \"||\" instead of \"or\"?";

  private static final ResourceReader resourceReader =
      new ResourceReader(PAppletJythonDriver.class);

  private static final String DETECT_MODE_SCRIPT = resourceReader.readText("detect_sketch_mode.py");

  private static final String PREPROCESS_SCRIPT = resourceReader.readText("pyde_preprocessor.py");

  static {
    // There's some bug that I don't understand yet that causes the native file
    // select box to fire twice, skipping confirmation the first time.
    useNativeSelect = false;
  }

  private PythonSketchError terminalException = null;

  protected final PyStringMap builtins;
  protected final InteractiveConsole interp;
  private final Path pySketchPath;
  private final String programText;
  private final WrappedPrintStream wrappedStdout;

  private final CountDownLatch finishedLatch = new CountDownLatch(1);

  private enum Mode {
    STATIC, ACTIVE, MIXED
  }

  // A static-mode sketch must be interpreted from within the setup() method.
  // All others are interpreted during construction in order to harvest method
  // definitions, which we then invoke during the run loop.
  private final Mode mode;

  /**
   * The Processing event handling functions can take 0 or 1 argument.
   * This class represents such a function.
   * <p>If the user did not implement the variant that takes an event,
   * then we have to pass through to the super implementation, or else
   * the zero-arg version won't get called.
   */
  private abstract class EventFunction<T> {
    private final PyFunction func;
    private final int argCount;

    protected abstract void callSuper(T event);

    public EventFunction(final String funcName) {
      func = (PyFunction)interp.get(funcName);
      argCount = func == null ? -1 : ((PyBaseCode)(func).__code__).co_argcount;
    }

    public void invoke() {
      if (func != null && argCount == 0) {
        func.__call__();
      }
    }

    public void invoke(final T event) {
      if (func != null && argCount == 1) {
        func.__call__(Py.java2py(event));
      } else {
        callSuper(event);
      }
    }
  }

  // These are all of the methods that PApplet might call in your sketch. If
  // you have implemented a method, we save it and call it.
  private PyObject setupMeth, settingsMeth, drawMeth, pauseMeth, resumeMeth, stopMeth;
  private EventFunction<KeyEvent> keyPressedFunc, keyReleasedFunc, keyTypedFunc;
  private EventFunction<MouseEvent> mousePressedFunc, mouseClickedFunc, mouseMovedFunc,
      mouseReleasedFunc, mouseDraggedFunc;
  private PyObject mouseWheelMeth; // Can only be called with a MouseEvent; no need for shenanigans

  // Implement the Video library's callback.
  private PyObject captureEventMeth, movieEventMeth;

  private SketchPositionListener sketchPositionListener;

  private void processSketch(final String scriptSource) throws PythonSketchError {
    try {
      interp.set("__processing_source__", programText);
      final PyCode code =
          Py.compile_flags(scriptSource, pySketchPath.toString(), CompileMode.exec,
              new CompilerFlags());
      interp.exec(code);
      Py.flushLine();
    } catch (Throwable t) {
      while (t.getCause() != null) {
        t = t.getCause();
      }
      throw toSketchException(t);
    }
  }

  /**
   * Handy method for raising a Python exception in the current interpreter frame.
   * @param msg TypeError message.
   */
  private PyObject raiseTypeError(final String msg) {
    interp.exec(String.format("raise TypeError('%s')", msg.replace("'", "\\'")));
    return Py.None;
  }

  private static PythonSketchError toSketchException(Throwable t) {
    if (t instanceof RuntimeException && t.getCause() != null) {
      t = t.getCause();
    }
    if (t instanceof PythonSketchError) {
      return (PythonSketchError)t;
    }
    if (t instanceof PySyntaxError) {
      final PySyntaxError e = (PySyntaxError)t;
      return extractSketchErrorFromPyExceptionValue((PyTuple)e.value);
    }
    if (t instanceof PyIndentationError) {
      final PyIndentationError e = (PyIndentationError)t;
      return extractSketchErrorFromPyExceptionValue((PyTuple)e.value);
    }
    if (t instanceof PyException) {
      final PyException e = (PyException)t;
      final Pattern tbParse =
          Pattern.compile("^\\s*File \"([^\"]+)\", line (\\d+)", Pattern.MULTILINE);
      final Matcher m = tbParse.matcher(e.toString());
      String file = null;
      int line = -1;
      while (m.find()) {
        final String fileName = m.group(1);
        // Ignore stack elements that come from exec()ing code in the interpreter from Java.
        if (fileName.equals("<string>")) {
          continue;
        }
        file = fileName;
        // Throw away the path.
        if (new File(file).exists()) {
          file = new File(file).getName();
        }
        line = Integer.parseInt(m.group(2)) - 1;
      }
      if (((PyType)e.type).getName().equals("ImportError")) {
        final Pattern importStar = Pattern.compile("import\\s+\\*");
        if (importStar.matcher(e.toString()).find()) {
          return new PythonSketchError("import * does not work in this environment.", file, line);
        }
      }
      return new PythonSketchError(Py.formatException(e.type, e.value), file, line);
    }
    final StringWriter stackTrace = new StringWriter();
    t.printStackTrace(new PrintWriter(stackTrace));
    return new PythonSketchError(stackTrace.toString());
  }

  private static PythonSketchError extractSketchErrorFromPyExceptionValue(final PyTuple tup) {
    final String pyMessage = (String)tup.get(0);
    final String message = maybeMakeFriendlyMessage(pyMessage);
    final PyTuple context = (PyTuple)tup.get(1);
    final File file = new File((String)context.get(0));
    final String fileName = file.getName();
    final int lineNumber = ((Integer)context.get(1)).intValue() - 1;
    final int column = ((Integer)context.get(2)).intValue();
    if (pyMessage.startsWith("no viable alternative")) {
      return noViableAlternative(file, lineNumber, column, pyMessage);
    }

    return new PythonSketchError(message, fileName, lineNumber, column);
  }

  private static final Pattern NAKED_COLOR = Pattern.compile("[(,]\\s*#([0-9a-fA-F]{6})\\b");

  /**
   * The message "no vialble alternative" is a strong indication that there's an unclosed
   * paren somewhere before the triggering line. Maybe the user tried to specify a color
   * as in Java Processing, like <code>fill(#FFAA55)</code>, which Python sees as an open
   * paren followed by a comment.
   * <p>This function takes a stab at finding such a thing, and reporting it. Otherwise,
   * it throws a slightly less cryptic error message.
   * @param file
   * @param line
   * @param column
   * @return
   */
  private static PythonSketchError noViableAlternative(final File file, final int lineNo,
      final int column, final String message) {
    if (message.equals("no viable alternative at input '&'")) {
      return new PythonSketchError(C_LIKE_LOGICAL_AND_ERROR_MESSAGE, file.getName(), lineNo, column);
    }
    if (message.equals("no viable alternative at input '|'")) {
      return new PythonSketchError(C_LIKE_LOGICAL_OR_ERROR_MESSAGE, file.getName(), lineNo, column);
    }
    final PythonSketchError defaultException =
        new PythonSketchError(
            "Maybe there's an unclosed paren or quote mark somewhere before this line?",
            file.getName(), lineNo, column);
    try {
      int lineIndex = 0;
      for (final String line : Files.readLines(file, Charsets.UTF_8)) {
        final Matcher m = NAKED_COLOR.matcher(line);
        if (m.find()) {
          final String color = m.group(1);
          return new PythonSketchError("Did you try to name a color here? "
              + "Colors in Python mode are either strings, like '#" + color + "', or "
              + "large hex integers, like 0xFF" + color.toUpperCase() + ".", file.getName(),
              lineIndex, m.start(1));
        }
        lineIndex++;
      }
    } catch (final IOException e) {
      System.err.println("While trying to read " + file + ": " + e.getMessage());
      return defaultException;
    }
    return defaultException;
  }

  private static String maybeMakeFriendlyMessage(final String message) {
    if (message.contains("expecting INDENT")) {
      return "This line probably needs to be indented.";
    }
    if (message.contains("mismatched input '//'")) {
      return "Did you mean to make a comment? "
          + "Comments in Python use the # character, not the double-slash.";
    }
    return message;
  }

  @Override
  public void frameMoved(final int x, final int y) {
    if (sketchPositionListener != null) {
      sketchPositionListener.sketchMoved(new Point(x, y));
    }
  }

  public PAppletJythonDriver(final InteractiveConsole interp, final String pySketchPath,
      final String programText, final Printer stdout) throws PythonSketchError {
    this.wrappedStdout = new WrappedPrintStream(System.out) {
      @Override
      public void doPrint(final String s) {
        stdout.print(s);
      }
    };
    this.programText = programText;
    this.pySketchPath = Paths.get(pySketchPath);
    this.interp = interp;
    this.builtins = (PyStringMap)interp.getSystemState().getBuiltins();

    interp.set("__file__", new File(pySketchPath).getName());
    processSketch(DETECT_MODE_SCRIPT);
    this.mode = Mode.valueOf(interp.get("__mode__").asString());
    Runner.log("Mode: ", mode.name());
    if (mode == Mode.MIXED) {
      throw interp.get("__error__", MixedModeError.class);
    }

    initializeStatics(builtins);
    setFilter();
    setMap();
    setSet();
    setColorMethods();
    setText();
    builtins.__setitem__("g", Py.java2py(g));

    // Make sure key and keyCode are defined.
    builtins.__setitem__("key", Py.newUnicode((char)0));
    builtins.__setitem__("keyCode", pyint(0));
  }

  @Override
  protected PSurface initSurface() {
    final PSurface s = super.initSurface();
    if (s instanceof PSurfaceAWT) {
      final PSurfaceAWT surf = (PSurfaceAWT)s;
      final Component c = (Component)surf.getNative();
      c.addComponentListener(new ComponentAdapter() {
        @Override
        public void componentHidden(final ComponentEvent e) {
          finishedLatch.countDown();
        }
      });
    } else if (s instanceof PSurfaceJOGL) {
      final PSurfaceJOGL surf = (PSurfaceJOGL)s;
      final GLWindow win = (GLWindow)surf.getNative();
      win.addWindowListener(new com.jogamp.newt.event.WindowAdapter() {
        @Override
        public void windowDestroyed(final com.jogamp.newt.event.WindowEvent arg0) {
          finishedLatch.countDown();
        }
      });
    }
    return s;
  }

  @Override
  public void exitActual() {
    finishedLatch.countDown();
  }

  public void findSketchMethods() throws PythonSketchError {
    if (mode == Mode.ACTIVE) {
      // Executing the sketch will bind method names ("draw") to PyCode
      // objects (the sketch's draw method), which can then be invoked
      // during the run loop
      processSketch(PREPROCESS_SCRIPT);
    }

    // Find and cache any PApplet callbacks defined in the Python sketch
    drawMeth = interp.get("draw");
    setupMeth = interp.get("setup");

    mousePressedFunc = new EventFunction<MouseEvent>("mousePressed") {
      @Override
      protected void callSuper(final MouseEvent event) {
        PAppletJythonDriver.super.mousePressed(event);
      }
    };
    mouseClickedFunc = new EventFunction<MouseEvent>("mouseClicked") {
      @Override
      protected void callSuper(final MouseEvent event) {
        PAppletJythonDriver.super.mouseClicked(event);
      }
    };
    mouseMovedFunc = new EventFunction<MouseEvent>("mouseMoved") {
      @Override
      protected void callSuper(final MouseEvent event) {
        PAppletJythonDriver.super.mouseMoved(event);
      }
    };
    mouseReleasedFunc = new EventFunction<MouseEvent>("mouseReleased") {
      @Override
      protected void callSuper(final MouseEvent event) {
        PAppletJythonDriver.super.mouseReleased(event);
      }
    };
    mouseDraggedFunc = new EventFunction<MouseEvent>("mouseDragged") {
      @Override
      protected void callSuper(final MouseEvent event) {
        PAppletJythonDriver.super.mouseDragged(event);
      }
    };

    keyPressedFunc = new EventFunction<KeyEvent>("keyPressed") {
      @Override
      protected void callSuper(final KeyEvent event) {
        PAppletJythonDriver.super.keyPressed(event);
      }
    };
    keyReleasedFunc = new EventFunction<KeyEvent>("keyReleased") {
      @Override
      protected void callSuper(final KeyEvent event) {
        PAppletJythonDriver.super.keyReleased(event);
      }
    };
    keyTypedFunc = new EventFunction<KeyEvent>("keyTyped") {
      @Override
      protected void callSuper(final KeyEvent event) {
        PAppletJythonDriver.super.keyTyped(event);
      }
    };

    settingsMeth = interp.get("settings");
    stopMeth = interp.get("stop");
    pauseMeth = interp.get("pause");
    resumeMeth = interp.get("resume");
    mouseWheelMeth = interp.get("mouseWheel");
    if (mousePressedFunc.func != null) {
      // The user defined a mousePressed() method, which will hide the magical
      // Processing variable boolean mousePressed. We have to do some magic.
      interp.getLocals().__setitem__("mousePressed", new PyBoolean(false) {
        @Override
        public boolean getBooleanValue() {
          return mousePressed;
        }

        @Override
        public PyObject __call__(final PyObject[] args, final String[] kws) {
          return mousePressedFunc.func.__call__(args, kws);
        }
      });
    }

    // Video library callbacks.
    captureEventMeth = interp.get("captureEvent");
    movieEventMeth = interp.get("movieEvent");
  }

  /*
   * Most of the time we're wrapping small, positive integers (like the mouse
   * position, the keyCode, and the frameCount). So we pre-allocate a bunch of
   * them to avoid garbage collection.
   */
  private static final PyInteger[] CHEAP_INTS = new PyInteger[20000];
  static {
    for (int i = 0; i < CHEAP_INTS.length; i++) {
      CHEAP_INTS[i] = Py.newInteger(i);
    }
  }

  private static PyInteger pyint(final int i) {
    return i >= 0 && i < CHEAP_INTS.length ? CHEAP_INTS[i] : Py.newInteger(i);
  }

  @Override
  public void focusGained() {
    super.focusGained();
    builtins.__setitem__("focused", Py.newBoolean(focused));
  }

  @Override
  public void focusLost() {
    super.focusLost();
    builtins.__setitem__("focused", Py.newBoolean(focused));
  }

  protected void wrapProcessingVariables() {
    wrapMouseVariables();
    wrapKeyVariables();
    builtins.__setitem__("width", pyint(width));
    builtins.__setitem__("height", pyint(height));
    builtins.__setitem__("displayWidth", pyint(displayWidth));
    builtins.__setitem__("displayHeight", pyint(displayHeight));
    builtins.__setitem__("focused", Py.newBoolean(focused));
    builtins.__setitem__("keyPressed", Py.newBoolean(keyPressed));
    builtins.__setitem__("frameCount", pyint(frameCount));
    builtins.__setitem__("frameRate", new PyFloat(frameRate) {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        switch (args.length) {
          default:
            return raiseTypeError("Can't call \"frameRate\" with " + args.length + " parameters.");
          case 1:
            frameRate((float)args[0].asDouble());
            return Py.None;
        }
      }
    });
  }

  // We only change the "key" variable as necessary to avoid generating
  // lots of PyUnicode garbage.
  private char lastKey = Character.MIN_VALUE;

  private void wrapKeyVariables() {
    if (lastKey != key) {
      lastKey = key;
      /*
       * If key is "CODED", i.e., an arrow key or other non-printable, pass that
       * value through as-is. If it's printable, convert it to a unicode string,
       * so that the user can compare key == 'x' instead of key == ord('x').
       */
      final PyObject pyKey = key == CODED ? pyint(key) : Py.newUnicode(key);
      builtins.__setitem__("key", pyKey);
    }
    builtins.__setitem__("keyCode", pyint(keyCode));
  }

  private void wrapMouseVariables() {
    builtins.__setitem__("mouseX", pyint(mouseX));
    builtins.__setitem__("mouseY", pyint(mouseY));
    builtins.__setitem__("pmouseX", pyint(pmouseX));
    builtins.__setitem__("pmouseY", pyint(pmouseY));
    builtins.__setitem__("mouseButton", pyint(mouseButton));
    if (mousePressedFunc.func == null) {
      builtins.__setitem__("mousePressed", Py.newBoolean(mousePressed));
    }
  }

  @Override
  public void start() {
    // I want to quit on runtime exceptions.
    // Processing just sits there by default.
    Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(final Thread t, final Throwable e) {
        terminalException = toSketchException(e);
        try {
          handleMethods("dispose");
        } catch (final Exception noop) {
          // give up
        }
        finishedLatch.countDown();
      }
    });
    super.start();
  }

  public void runAndBlock(final String[] args) throws PythonSketchError {
    PApplet.runSketch(args, this);
    try {
      finishedLatch.await();
    } catch (final InterruptedException interrupted) {
      // Treat an interruption as a request to the applet to terminate.
      exit();
      try {
        finishedLatch.await();
      } catch (final InterruptedException e) {
        // fallthrough
      }
    } finally {
      if (PApplet.platform == PConstants.MACOSX && Arrays.asList(args).contains("fullScreen")) {
        // Frame should be OS-X fullscreen, and it won't stop being that unless the jvm
        // exits or we explicitly tell it to minimize.
        // (If it's disposed, it'll leave a gray blank window behind it.)
        Runner.log("Disabling fullscreen.");
        macosxFullScreenToggle(frame);
      }
      surface.setVisible(false);
    }
    if (terminalException != null) {
      throw terminalException;
    }
  }

  /**
   * Use reflection to call
   * <code>com.apple.eawt.Application.getApplication().requestToggleFullScreen(window);</code>
   */
  static private void macosxFullScreenToggle(final Window window) {
    try {
      final Class<?> appClass = Class.forName("com.apple.eawt.Application");
      final Method getAppMethod = appClass.getMethod("getApplication");
      final Object app = getAppMethod.invoke(null);
      final Method requestMethod = appClass.getMethod("requestToggleFullScreen", Window.class);
      requestMethod.invoke(app, window);
    } catch (final ClassNotFoundException cnfe) {
      // ignored
    } catch (final Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Permit the punning use of set() by mucking with the builtin "set" Type.
   * If you call it with 3 arguments, it acts like the Processing set(x, y,
   * whatever) method. If you call it with 0 or 1 args, it constructs a Python
   * set.
   */
  private void setSet() {
    final PyType originalSet = (PyType)builtins.__getitem__("set");
    builtins.__setitem__("set", new PyType(PyType.TYPE) {
      {
        builtin = true;
        init(PySet.class, new HashSet<PyJavaType>());
        invalidateMethodCache();
      }

      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        switch (args.length) {
          default:
            return originalSet.__call__(args, kws);
          case 3:
            final int x = args[0].asInt();
            final int y = args[1].asInt();
            final PyObject c = args[2];
            final PyType tc = c.getType();
            if (tc.getProxyType() != null && PImage.class.isAssignableFrom(tc.getProxyType())) {
              set(x, y, (processing.core.PImage)c.__tojava__(processing.core.PImage.class));
              return Py.None;
            } else {
              set(x, y, interpretColorArg(c));
              return Py.None;
            }
        }
      }
    });
  }

  /**
   * Permit both the Processing map() (which is a linear interpolation function) and
   * the Python map() (which is a list transformation).
   */
  private void setMap() {
    final PyObject builtinMap = builtins.__getitem__("map");
    builtins.__setitem__("map", new PyObject() {

      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        switch (args.length) {
          default:
            return builtinMap.__call__(args, kws);
          case 5:
            final PyObject value = args[0];
            final PyObject start1 = args[1];
            final PyObject stop1 = args[2];
            final PyObject start2 = args[3];
            final PyObject stop2 = args[4];
            if (value.isNumberType() && start1.isNumberType() && stop1.isNumberType()
                && start2.isNumberType() && stop2.isNumberType()) {
              return Py.newFloat(map((float)value.asDouble(), (float)start1.asDouble(),
                  (float)stop1.asDouble(), (float)start2.asDouble(), (float)stop2.asDouble()));
            } else {
              return builtinMap.__call__(args, kws);
            }
        }
      }
    });
  }

  /**
   * Permit both the Processing filter() (which does image processing) and the
   * Python filter() (which does list comprehensions).
   */
  private void setFilter() {
    final PyObject builtinFilter = builtins.__getitem__("filter");
    builtins.__setitem__("filter", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        switch (args.length) {
          case 1:
            final PyObject value = args[0];
            if (value.isNumberType()) {
              filter(value.asInt());
            } else {
              filter(Py.tojava(value, PShader.class));
            }
            return Py.None;
          case 2:
            final PyObject a = args[0];
            final PyObject b = args[1];
            if (a.isNumberType()) {
              filter(a.asInt(), (float)b.asDouble());
              return Py.None;
            }
            //$FALL-THROUGH$
          default:
            return builtinFilter.__call__(args, kws);
        }
      }
    });
  }

  // If you call lerpColor in an active-mode sketch before setup() has run,
  // there's no current graphics context, and it NPEs. This protects you from
  // that.
  @Override
  public int lerpColor(final int c1, final int c2, final float amt) {
    if (g != null) {
      return super.lerpColor(c1, c2, amt);
    }
    return PApplet.lerpColor(c1, c2, amt, RGB); // use the default mode
  }

  /*
   * If you fill(0xAARRGGBB), for some reason Jython decides to
   * invoke the fill(float) method, unless we provide a long int
   * version to catch it.
   */
  public void fill(final long argb) {
    fill((int)(argb & 0xFFFFFFFF));
  }

  public void stroke(final long argb) {
    stroke((int)(argb & 0xFFFFFFFF));
  }

  public void background(final long argb) {
    background((int)(argb & 0xFFFFFFFF));
  }

  /*
   * Python can't parse web colors, so we let the user do '#RRGGBB'
   * as a string.
   */
  public void fill(final String argbSpec) {
    fill(parseColorSpec(argbSpec));
  }

  public void stroke(final String argbSpec) {
    stroke(parseColorSpec(argbSpec));
  }

  public void background(final String argbSpec) {
    background(parseColorSpec(argbSpec));
  }

  private int parseColorSpec(final String argbSpec) {
    try {
      return 0xFF000000 | Integer.decode(argbSpec);
    } catch (final NumberFormatException e) {
      return raiseTypeError("I can't understand \"" + argbSpec + "\" as a color.").asInt();
    }
  }

  private boolean isString(final PyObject o) {
    return o.getType() == PyString.TYPE || o.getType() == PyUnicode.TYPE;
  }

  /**
   * The positional arguments to lerpColor may be long integers or CSS-style
   * string specs.
   * @param arg A color argument.
   * @return the integer correspnding to the intended color.
   */
  private int interpretColorArg(final PyObject arg) {
    return isString(arg) ? parseColorSpec(arg.asString()) : arg.asInt();
  }

  /**
   * Permit both the instance method lerpColor and the static method lerpColor.
   * Also permit 0xAARRGGBB, '#RRGGBB', and 0-255.
   */
  private void setColorMethods() {
    builtins.__setitem__("lerpColor", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        final int c1 = interpretColorArg(args[0]);
        final int c2 = interpretColorArg(args[1]);
        final float amt = (float)args[2].asDouble();
        switch (args.length) {
          case 3:
            return pyint(lerpColor(c1, c2, amt));
          case 4:
            final int colorMode = (int)(args[3].asLong() & 0xFFFFFFFF);
            return pyint(lerpColor(c1, c2, amt, colorMode));
            //$FALL-THROUGH$
          default:
            return raiseTypeError("lerpColor takes either 3 or 4 arguments, but I got "
                + args.length + ".");
        }
      }
    });
    builtins.__setitem__("alpha", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(alpha(interpretColorArg(args[0])));
      }
    });
    builtins.__setitem__("red", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(red(interpretColorArg(args[0])));
      }
    });
    builtins.__setitem__("green", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(green(interpretColorArg(args[0])));
      }
    });
    builtins.__setitem__("blue", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(blue(interpretColorArg(args[0])));
      }
    });
    builtins.__setitem__("hue", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(hue(interpretColorArg(args[0])));
      }
    });
    builtins.__setitem__("saturation", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(saturation(interpretColorArg(args[0])));
      }
    });
    builtins.__setitem__("brightness", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        return Py.newFloat(brightness(interpretColorArg(args[0])));
      }
    });
  }

  /**
   * Hide the variants of text() that take char/char[] and array indices, since that's
   * not necessary in Python, and since they mask the text()s that take take strings.
   */
  private void setText() {
    builtins.__setitem__("text", new PyObject() {
      @Override
      public PyObject __call__(final PyObject[] args, final String[] kws) {
        if (args.length < 3 || args.length > 5) {
          raiseTypeError("text() takes 3-5 arguments, but I got " + args.length + ".");
        }
        final PyObject a = args[0];
        final float x1 = (float)args[1].asDouble();
        final float y1 = (float)args[2].asDouble();
        if (args.length == 3) {
          if (isString(a)) {
            text(a.asString(), x1, y1);
          } else if (a.getType() == PyInteger.TYPE) {
            text(a.asInt(), x1, y1);
          } else {
            text((float)a.asDouble(), x1, y1);
          }
        } else if (args.length == 4) {
          final float z1 = (float)args[3].asDouble();
          if (isString(a)) {
            text(a.asString(), x1, y1, z1);
          } else if (a.getType() == PyInteger.TYPE) {
            text(a.asInt(), x1, y1, z1);
          } else {
            text((float)a.asDouble(), x1, y1, z1);
          }
        } else /* 5 */{
          text(a.asString(), x1, y1, (float)args[3].asDouble(), (float)args[4].asDouble());
        }
        return Py.None;
      }
    });
  }

  /**
   * Populate the Python builtins namespace with PConstants.
   */
  public static void initializeStatics(final PyStringMap builtins) {
    for (final Field f : PConstants.class.getDeclaredFields()) {
      final int mods = f.getModifiers();
      if (Modifier.isPublic(mods) || Modifier.isStatic(mods)) {
        try {
          builtins.__setitem__(f.getName(), Py.java2py(f.get(null)));
        } catch (final Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * We have to override PApplet's size method in order to reset the Python
   * context's knowledge of the magic variables that reflect the state of the
   * sketch's world, particularly width and height.
   */
  @Override
  public void size(final int iwidth, final int iheight, final String irenderer, final String ipath) {
    super.size(iwidth, iheight, irenderer, ipath);
    builtins.__setitem__("g", Py.java2py(g));
    builtins.__setitem__("frame", Py.java2py(frame));
    builtins.__setitem__("width", pyint(width));
    builtins.__setitem__("height", pyint(height));
  }

  @Override
  public void settings() {
    try {
      if (settingsMeth != null) {
        settingsMeth.__call__();
      } else {
        super.settings();
      }
    } catch (final Exception e) {
      terminalException = toSketchException(e);
      exitActual();
    }
  }

  @Override
  public void setup() {
    builtins.__setitem__("frame", Py.java2py(frame));
    wrapProcessingVariables();
    try {
      if (mode == Mode.STATIC) {
        // A static sketch gets called once, from this spot.
        Runner.log("Interpreting static-mode sketch.");
        processSketch(PREPROCESS_SCRIPT);
      } else if (setupMeth != null) {
        // Call the Python sketch's setup()
        setupMeth.__call__();
      }
    } catch (final Exception e) {
      terminalException = toSketchException(e);
      exitActual();
    }
  }

  @Override
  public void draw() {
    wrapProcessingVariables();
    if (drawMeth == null) {
      super.draw();
    } else if (!finished) {
      drawMeth.__call__();
    }
  }

  @Override
  public void loadPixels() {
    super.loadPixels();
    builtins.__setitem__("pixels", Py.java2py(pixels));
  }

  @Override
  public void mouseClicked() {
    wrapMouseVariables();
    mouseClickedFunc.invoke();
  }

  @Override
  public void mouseClicked(final MouseEvent e) {
    wrapMouseVariables();
    mouseClickedFunc.invoke(e);
  }

  @Override
  public void mouseMoved() {
    wrapMouseVariables();
    mouseMovedFunc.invoke();
  }

  @Override
  public void mouseMoved(final MouseEvent e) {
    wrapMouseVariables();
    mouseMovedFunc.invoke(e);
  }

  @Override
  public void mousePressed() {
    wrapMouseVariables();
    mousePressedFunc.invoke();
  }

  @Override
  public void mousePressed(final MouseEvent e) {
    wrapMouseVariables();
    mousePressedFunc.invoke(e);
  }

  @Override
  public void mouseReleased() {
    wrapMouseVariables();
    mouseReleasedFunc.invoke();
  }

  @Override
  public void mouseReleased(final MouseEvent e) {
    wrapMouseVariables();
    mouseReleasedFunc.invoke(e);
  }


  @Override
  public void mouseDragged() {
    wrapMouseVariables();
    mouseDraggedFunc.invoke();
  }

  @Override
  public void mouseDragged(final MouseEvent e) {
    wrapMouseVariables();
    mouseDraggedFunc.invoke(e);
  }

  @Override
  public void mouseWheel(final MouseEvent e) {
    if (mouseWheelMeth != null) {
      wrapMouseVariables();
      mouseWheelMeth.__call__(Py.java2py(e));
    }
  }

  @Override
  public void keyPressed() {
    wrapKeyVariables();
    keyPressedFunc.invoke();
  }

  @Override
  public void keyPressed(final KeyEvent e) {
    wrapKeyVariables();
    keyPressedFunc.invoke(e);
  }

  @Override
  public void keyReleased() {
    wrapKeyVariables();
    keyReleasedFunc.invoke();
  }

  @Override
  public void keyReleased(final KeyEvent e) {
    wrapKeyVariables();
    keyReleasedFunc.invoke(e);
  }


  @Override
  public void keyTyped() {
    wrapKeyVariables();
    keyTypedFunc.invoke();
  }

  @Override
  public void keyTyped(final KeyEvent e) {
    wrapKeyVariables();
    keyTypedFunc.invoke(e);
  }

  @Override
  public void stop() {
    try {
      if (stopMeth != null) {
        stopMeth.__call__();
      }
    } finally {
      super.stop();
    }
  }

  @Override
  public void pause() {
    try {
      if (pauseMeth != null) {
        pauseMeth.__call__();
      }
    } finally {
      super.pause();
    }
  }

  @Override
  public void resume() {
    try {
      if (resumeMeth != null) {
        resumeMeth.__call__();
      }
    } finally {
      super.resume();
    }
  }

  /**
   * Processing uses reflection to call file selection callbacks by name.
   * We fake out that stuff with one of these babies.
   */
  public class FileSelectCallbackProxy {
    private final PyObject callback;

    public FileSelectCallbackProxy(final String name) {
      callback = interp.get(name);
      if (callback == null) {
        throw new RuntimeException("I can't find a callback function named \"" + name + "\"");
      }
    }

    // Called only by reflection.
    @SuppressWarnings("unused")
    public void handleCallback(final File selection) {
      callback.__call__(Py.java2py(selection));
    }
  }

  @Override
  public void selectFolder(final String prompt, final String callback, final File file) {
    super.selectFolder(prompt, "handleCallback", file, new FileSelectCallbackProxy(callback));
  }

  @Override
  public void selectInput(final String prompt, final String callback, final File file) {
    super.selectInput(prompt, "handleCallback", file, new FileSelectCallbackProxy(callback));
  }

  @Override
  public void selectOutput(final String prompt, final String callback, final File file) {
    super.selectOutput(prompt, "handleCallback", file, new FileSelectCallbackProxy(callback));
  }

  // Some PApplet builtins print directly to stdout rather than using PApplet's print()
  @Override
  public void printMatrix() {
    try (PushedOut w = wrappedStdout.pushStdout()) {
      super.printMatrix();
    }
  }

  @Override
  public void printCamera() {
    try (PushedOut w = wrappedStdout.pushStdout()) {
      super.printCamera();
    }
  }

  @Override
  public void printProjection() {
    try (PushedOut w = wrappedStdout.pushStdout()) {
      super.printProjection();
    }
  }

  // TODO(feinberg): Patch PApplet to make printArray non-static, so we can
  // implement it here.

  // Video library callbacks.
  public void captureEvent(final Object capture) {
    if (captureEventMeth != null) {
      captureEventMeth.__call__(Py.java2py(capture));
    }
  }

  public void movieEvent(final Object movie) {
    if (movieEventMeth != null) {
      movieEventMeth.__call__(Py.java2py(movie));
    }
  }

  public void setSketchPositionListener(final SketchPositionListener sketchPositionListener) {
    this.sketchPositionListener = sketchPositionListener;
  }
}
