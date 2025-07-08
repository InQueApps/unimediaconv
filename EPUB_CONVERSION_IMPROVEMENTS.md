# EPUB to PDF Conversion Improvements

## Overview
The EPUB to PDF conversion has been significantly enhanced to better preserve images, styles, and overall document formatting. This document outlines the key improvements and new features.

## Key Improvements

### 1. **Enhanced EPUB Parsing**
- **Proper EPUB Structure Understanding**: Now parses `container.xml` and `content.opf` files to understand the EPUB structure
- **Spine Order Preservation**: Maintains the correct reading order of chapters as defined in the EPUB spine
- **Metadata Extraction**: Extracts book title, author, language, and identifier from EPUB metadata
- **Comprehensive File Detection**: Supports more image formats (JPG, PNG, GIF, SVG, WebP, BMP)

### 2. **Improved CSS Handling**
- **CSS URL Resolution**: Automatically resolves relative URLs in CSS files to absolute paths
- **Enhanced Default Styling**: Provides comprehensive default CSS for better PDF formatting
- **Style Preservation**: Better preservation of inline styles and external stylesheets
- **Print-Optimized CSS**: Includes page break controls and print-specific styling

### 3. **Image Processing Enhancements**
- **Relative Path Resolution**: Correctly resolves relative image paths in HTML content
- **Background Image Support**: Handles background images in CSS and inline styles
- **Image Format Support**: Supports multiple image formats with proper MIME type detection
- **Image Optimization**: Ensures images are properly sized and positioned in PDF output

### 4. **PDF Generation Improvements**
- **Enhanced iText Configuration**: Uses advanced iText settings for better HTML to PDF conversion
- **Font Embedding**: Enables font embedding for consistent typography
- **Media Device Configuration**: Optimizes rendering for A4 page size
- **PDF Metadata**: Adds proper PDF metadata including title, author, and creator information

### 5. **Better Error Handling**
- **Graceful Fallbacks**: Provides enhanced fallback PDF generation when conversion fails
- **Detailed Error Reporting**: Better error messages and debugging information
- **Resource Cleanup**: Proper cleanup of temporary files and resources

## Technical Details

### EPUB Structure Parsing
```kotlin
// Parses container.xml to find content.opf location
private fun extractContainerInfo(epubFile: File): ContainerInfo

// Parses content.opf to understand manifest and spine
private fun extractContentOpfInfo(epubFile: File, contentOpfPath: String): ContentOpfInfo

// Extracts metadata from content.opf
private fun parseMetadata(contentOpfContent: String): EpubMetadata
```

### CSS Processing
```kotlin
// Resolves relative URLs in CSS to absolute paths
private fun resolveCssUrls(cssContent: String, baseDir: File): String

// Processes all CSS files with URL resolution
private fun processCssFiles(cssFiles: List<CssFile>, tempDir: File): List<CssFile>
```

### Image Processing
```kotlin
// Processes images in HTML content
private fun processImagesInHtml(element: Element, tempDir: File): Element

// Resolves relative paths to absolute paths
private fun resolveRelativePath(url: String, baseDir: File): String
```

### Enhanced PDF Generation
```kotlin
// Enhanced HTML to PDF conversion with better settings
private fun convertHtmlToPdfEnhanced(
    htmlFile: File, 
    outputFile: File, 
    baseDir: File, 
    metadata: EpubMetadata
)
```

## CSS Enhancements

### Default Styling
The enhanced converter includes comprehensive default CSS that provides:

- **Page Layout**: A4 page size with 1-inch margins
- **Typography**: Times New Roman font with proper line spacing
- **Headings**: Hierarchical heading styles with page break controls
- **Images**: Responsive image sizing with page break avoidance
- **Tables**: Proper table formatting with borders and spacing
- **Lists**: Well-formatted ordered and unordered lists
- **Code Blocks**: Styled code and preformatted text
- **Links**: Proper link styling and colors

### Print-Specific CSS
```css
@page {
    margin: 1in;
    size: A4;
}

.chapter { 
    page-break-before: always; 
}

.chapter:first-child { 
    page-break-before: avoid; 
}

img { 
    page-break-inside: avoid;
}
```

## Supported Features

### Image Formats
- JPEG (.jpg, .jpeg)
- PNG (.png)
- GIF (.gif)
- SVG (.svg)
- WebP (.webp)
- BMP (.bmp)

### CSS Features
- External stylesheets
- Inline styles
- Background images
- Relative URL resolution
- Print media queries

### EPUB Features
- EPUB 2.0 and 3.0 support
- Navigation documents
- Multiple HTML chapters
- Embedded fonts (basic support)
- Metadata preservation

## Usage

The enhanced EPUB converter is used the same way as before:

```kotlin
val epubConverter = EpubConverter()
val pdfFile = epubConverter.convertEpubToPdf(epubFile, context)
```

## Performance Considerations

- **Memory Usage**: Large EPUBs with many images may require more memory
- **Processing Time**: Enhanced parsing and CSS processing may take slightly longer
- **File Size**: Better image preservation may result in larger PDF files
- **Temporary Storage**: More temporary files are created during processing

## Future Enhancements

Potential areas for further improvement:

1. **Font Embedding**: Better support for embedded fonts in EPUBs
2. **Advanced Layout**: Support for complex layouts and positioning
3. **Interactive Elements**: Support for EPUB3 interactive features
4. **MathML Support**: Better mathematical formula rendering
5. **Accessibility**: Enhanced accessibility features in PDF output

## Testing

The enhanced converter includes comprehensive tests:

- Unit tests for data classes and helper functions
- Integration tests with sample EPUB files
- Error handling tests
- Performance tests for large files

## Dependencies

The enhanced converter requires:

- **iText 7**: For PDF generation
- **JSoup**: For HTML parsing and manipulation
- **Android Core**: For file operations and image processing

## Troubleshooting

### Common Issues

1. **Images Not Appearing**: Check that image paths are correctly resolved
2. **Styles Not Applied**: Verify CSS files are properly extracted and processed
3. **Large File Sizes**: Consider image compression for very large EPUBs
4. **Memory Issues**: For very large files, consider processing in chunks

### Debug Information

Enable debug logging to see detailed conversion information:

```kotlin
// Add logging to track conversion progress
android.util.Log.d("EpubConverter", "Processing file: ${fileName}")
``` 