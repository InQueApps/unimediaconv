# Universal Converter

A beautiful Android app that converts between EPUB and PDF formats with an engaging user interface.

## Features

- **EPUB to PDF Conversion**: Convert EPUB e-books to PDF format while preserving formatting and images
- **PDF to EPUB Conversion**: Convert PDF documents to EPUB format for better e-reader compatibility
- **Beautiful UI**: Modern Material Design 3 interface with smooth animations and intuitive controls
- **File Sharing**: Easily share converted files with other apps
- **Progress Tracking**: Real-time conversion progress with visual feedback
- **Error Handling**: Comprehensive error handling with user-friendly messages

## How It Works

### EPUB to PDF Conversion
The app extracts content from EPUB files (which are essentially ZIP files containing HTML, CSS, and images) and converts them to PDF format using the iText library. The conversion process:

1. Extracts HTML content, CSS styles, and images from the EPUB
2. Combines all HTML files into a single document
3. Applies CSS styling for proper formatting
4. Converts the combined HTML to PDF using iText's HTML to PDF converter
5. Preserves images and maintains document structure

### PDF to EPUB Conversion
The app extracts text content from PDF files and creates a properly structured EPUB file:

1. Extracts text from all pages of the PDF
2. Creates the necessary EPUB structure (container.xml, content.opf, toc.ncx)
3. Generates HTML content with proper styling
4. Packages everything into a valid EPUB file

## Technical Details

### Dependencies
- **iText 7**: For PDF creation and manipulation
- **JSoup**: For HTML parsing and manipulation
- **Jetpack Compose**: For modern UI development
- **Coroutines**: For asynchronous operations
- **Material Design 3**: For beautiful, accessible UI components

### Architecture
- **MVVM Pattern**: Using ViewModel and StateFlow for state management
- **Repository Pattern**: Separate converter classes for different file formats
- **Composable UI**: Declarative UI using Jetpack Compose

## Usage

1. **Launch the App**: Open Universal Converter on your Android device
2. **Select File**: Tap "Choose File" to select an EPUB or PDF file
3. **Convert**: Tap "Convert" to start the conversion process
4. **Monitor Progress**: Watch the progress bar as your file is converted
5. **Share Result**: Use the "Share File" button to share your converted file

## File Support

### Supported Input Formats
- **EPUB**: Standard EPUB e-book files (.epub)
- **PDF**: Adobe PDF documents (.pdf)

### Output Formats
- **PDF**: High-quality PDF files with preserved formatting
- **EPUB**: Standard EPUB files compatible with most e-readers

## Permissions

The app requires the following permissions:
- **Storage Access**: To read and write files for conversion
- **File Sharing**: To share converted files with other apps

## Development

### Building the Project
1. Clone the repository
2. Open the project in Android Studio
3. Sync Gradle dependencies
4. Build and run on an Android device or emulator

### Project Structure
```
app/src/main/java/ab/inque/converter/
├── MainActivity.kt              # Main activity with UI
├── ConverterViewModel.kt        # ViewModel for state management
├── EpubConverter.kt            # EPUB to PDF conversion logic
└── PdfConverter.kt             # PDF to EPUB conversion logic
```

## Limitations

- **Complex PDFs**: Very complex PDFs with advanced formatting may not convert perfectly
- **Large Files**: Very large files may take longer to convert
- **Protected Content**: DRM-protected files cannot be converted
- **Images**: Some complex image layouts may be simplified during conversion

## Future Enhancements

- Support for more file formats (MOBI, AZW3, etc.)
- Batch conversion capabilities
- Cloud storage integration
- Advanced formatting options
- OCR support for image-based PDFs

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- **iText**: For excellent PDF manipulation capabilities
- **JSoup**: For robust HTML parsing
- **Google**: For Material Design 3 and Jetpack Compose
- **Android Community**: For continuous improvements to the platform 