/*
 * FopTask.java
 * Copyright 2016 Connor Petty <cpmeister@users.sourceforge.net>
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 * Created on Jan 3, 2016, 9:14:07 PM
 */
package pcgen.util.fop;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.SourceLocator;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.fop.apps.FOPException;
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.render.Renderer;

import pcgen.cdom.base.Constants;
import pcgen.system.ConfigurationSettings;
import pcgen.util.Logging;

/**
 * This class is used to generate pdf files from an xml source. There are two ways to define the
 * source of the task: files or inputstreams. The output of this task can either be an OutputStream
 * which you can point to a file, or a Renderer. The Renderer is used by print preview and for
 * direct printing.
 *
 * @author Connor Petty <cpmeister@users.sourceforge.net>
 */
public class FopTask implements Runnable
{

	private static final TransformerFactory TRANS_FACTORY = TransformerFactory.newInstance();

	private static FopFactory createFopFactory()
	{
		FopFactory fopFactory = FopFactory.newInstance();
		fopFactory.setStrictValidation(false);

		// Allow optional customization with configuration file
		String configPath = ConfigurationSettings.getOutputSheetsDir() + File.separator + "fop.xconf";
		Logging.log(Logging.INFO, "Checking for config file at " + configPath);
		File userConfigFile = new File(configPath);
		if (userConfigFile.exists())
		{
			Logging.log(Logging.INFO, "FoPTask using config file "
					+ configPath);
			try
			{
				fopFactory.setUserConfig(userConfigFile);
			}
			catch (Exception e)
			{
				Logging.errorPrint("Problem with FOP configuration "
						+ configPath + ": ", e);
			}
		}
		return fopFactory;
	}

	private final StreamSource inputSource;
	private final StreamSource xsltSource;
	private final Renderer renderer;
	private final OutputStream outputStream;

	private StringBuilder errorBuilder = new StringBuilder(32);

	private FopTask(StreamSource inputXml, StreamSource xsltSource, Renderer renderer, OutputStream outputStream)
	{
		this.inputSource = inputXml;
		this.xsltSource = xsltSource;
		this.renderer = renderer;
		this.outputStream = outputStream;
	}

	private static StreamSource createXsltStreamSource(File xsltFile) throws FileNotFoundException
	{
		if (xsltFile == null)
		{
			return null;
		}
		if (!xsltFile.exists())
		{
			throw new FileNotFoundException("xsl file "
					+ xsltFile.getAbsolutePath() + " not found ");
		}
		return new StreamSource(xsltFile);
	}

	/**
	 * Creates a new FopTask that transforms the input stream using the given xsltFile and outputs a
	 * pdf document to the given output stream. The output can be saved to a file if a
	 * FileOutputStream is used.
	 *
	 * @param inputXmlStream the fop xml input stream
	 * @param xsltFile the transform template file, if null then the identity transformer is used
	 * @param outputPdf output stream for pdf document
	 * @return a FopTask to be executed
	 * @throws FileNotFoundException if xsltFile is not null and does not exist
	 */
	public static FopTask newFopTask(InputStream inputXmlStream, File xsltFile, OutputStream outputPdf) throws FileNotFoundException
	{
		StreamSource xsltSource = createXsltStreamSource(xsltFile);
		return new FopTask(new StreamSource(inputXmlStream), xsltSource, null, outputPdf);
	}

	/**
	 * Creates a new FopTask that transforms the input stream using the given xsltFile and outputs a
	 * pdf document to the given Renderer. This task can can be used for both previewing a pdf
	 * document as well as printing a pdf
	 *
	 * @param inputXmlStream the fop xml input stream
	 * @param xsltFile the transform template file, if null then the identity transformer is used
	 * @param renderer the Renderer to output a pdf document to.
	 * @return a FopTask to be executed
	 * @throws FileNotFoundException if xsltFile is not null and does not exist
	 */
	public static FopTask newFopTask(InputStream inputXmlStream, File xsltFile, Renderer renderer) throws FileNotFoundException
	{
		StreamSource xsltSource = createXsltStreamSource(xsltFile);
		return new FopTask(new StreamSource(inputXmlStream), xsltSource, renderer, null);
	}

	public String getErrorMessages()
	{
		return errorBuilder.toString();
	}

	/**
	 * Run the FO to PDF/AWT conversion. This automatically closes any provided OutputStream for
	 * this FopTask.
	 */
	@Override
	public void run()
	{
		try(OutputStream out = outputStream)
		{
			FopFactory factory = createFopFactory();

			FOUserAgent userAgent = factory.newFOUserAgent();
			userAgent.setProducer("PC Gen Character Generator");
			userAgent.setAuthor(System.getProperty("user.name"));
			userAgent.setCreationDate(new Date());

			String mimeType;
			if (renderer != null)
			{
				userAgent.setKeywords("PCGEN FOP PREVIEW");
				userAgent.setRendererOverride(renderer);
				renderer.setUserAgent(userAgent);
				mimeType = MimeConstants.MIME_FOP_AWT_PREVIEW;
			}
			else
			{
				userAgent.setKeywords("PCGEN FOP PDF");
				mimeType = MimeConstants.MIME_PDF;
			}
			Fop fop;
			if (out != null)
			{
				fop = factory.newFop(mimeType, userAgent, out);
			}
			else
			{
				fop = factory.newFop(mimeType, userAgent);
			}

			Transformer transformer;
			if (xsltSource != null)
			{
				transformer = TRANS_FACTORY.newTransformer(xsltSource);
			}
			else
			{
				transformer = TRANS_FACTORY.newTransformer();// identity transformer		
			}
			transformer.setErrorListener(new FOPErrorListener());
			transformer.transform(inputSource, new SAXResult(fop.getDefaultHandler()));
		}
		catch (TransformerException | FOPException | IOException e)
		{
			errorBuilder.append(e.getMessage()).append(Constants.LINE_SEPARATOR);
			Logging.errorPrint("Exception in FopTask:run", e);
		}
		catch (RuntimeException ex)
		{
			errorBuilder.append(ex.getMessage()).append(Constants.LINE_SEPARATOR);
			Logging.errorPrint("Unexpected exception in FopTask:run: ", ex);
		}
	}

	/**
	 * The Class <code>FOPErrorListener</code> listens for notifications of issues when generating
	 * PDF files and responds accordingly.
	 */
	public static class FOPErrorListener implements ErrorListener
	{

		/**
		 * @{inheritdoc}
		 */
		@Override
		public void error(TransformerException exception)
				throws TransformerException
		{
			SourceLocator locator = exception.getLocator();
			Logging.errorPrint("FOP Error " + exception.getMessage() + " at " + getLocation(locator));
			throw exception;
		}

		/**
		 * @{inheritdoc}
		 */
		@Override
		public void fatalError(TransformerException exception)
				throws TransformerException
		{
			SourceLocator locator = exception.getLocator();
			Logging.errorPrint("FOP Fatal Error " + exception.getMessage() + " at " + getLocation(locator));
			throw exception;
		}

		/**
		 * @{inheritdoc}
		 */
		@Override
		public void warning(TransformerException exception)
				throws TransformerException
		{
			SourceLocator locator = exception.getLocator();
			Logging.log(Logging.WARNING, getLocation(locator) + exception.getMessage());
		}

		private String getLocation(SourceLocator locator)
		{
			if (locator == null)
			{
				return "Unknown; ";
			}
			StringBuilder builder = new StringBuilder();
			if (locator.getSystemId() != null)
			{
				builder.append(locator.getSystemId());
				builder.append("; ");
			}
			if (locator.getLineNumber() > -1)
			{
				builder.append("Line#: ");
				builder.append(locator.getLineNumber());
				builder.append("; ");
			}
			if (locator.getColumnNumber() > -1)
			{
				builder.append("Column#: ");
				builder.append(locator.getColumnNumber());
				builder.append("; ");
			}
			return builder.toString();
		}

	}

}