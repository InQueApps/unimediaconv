import sys
import os
from ebooklib import epub
from bs4 import BeautifulSoup
from weasyprint import HTML
import tempfile
import shutil


def extract_epub_to_html(epub_path, temp_dir):
    book = epub.read_epub(epub_path)
    html_files = []
    css_files = []
    
    # First pass: collect all items
    for item in book.get_items():
        if item.get_type() == 9:  # HTML documents
            html_files.append(item)
        elif item.get_type() == 1:  # Images
            # Save images to temp_dir
            img_path = os.path.join(temp_dir, item.file_name)
            os.makedirs(os.path.dirname(img_path), exist_ok=True)
            with open(img_path, 'wb') as f:
                f.write(item.get_content())
        elif item.get_type() == 2:  # CSS files
            css_files.append(item)
            # Save CSS to temp_dir
            css_path = os.path.join(temp_dir, item.file_name)
            os.makedirs(os.path.dirname(css_path), exist_ok=True)
            with open(css_path, 'w', encoding='utf-8') as f:
                f.write(item.get_content().decode('utf-8'))
    
    # Combine HTML files into one
    html_content = '<!DOCTYPE html><html><head><meta charset="utf-8"><title>EPUB to PDF</title>'
    
    # Add CSS links
    for css_item in css_files:
        html_content += f'<link rel="stylesheet" type="text/css" href="{css_item.file_name}">'
    
    # Add inline styles from HTML files and find referenced CSS
    all_styles = set()  # Use set to avoid duplicates
    referenced_css = set()
    
    for html_item in html_files:
        soup = BeautifulSoup(html_item.get_content(), 'html.parser')
        # Extract styles from head
        head = soup.find('head')
        if head:
            styles = head.find_all('style')
            for style in styles:
                all_styles.add(str(style))
            # Look for link tags to CSS files
            links = head.find_all('link', rel='stylesheet')
            for link in links:
                href = link.get('href')
                if href:
                    referenced_css.add(href)
                    # Try to find the CSS file in the EPUB
                    for item in book.get_items():
                        if (item.file_name.endswith(href.split('/')[-1]) or 
                            item.file_name.endswith(href) or
                            item.file_name.endswith(href.split('\\')[-1])):
                            if item.get_type() == 2:  # CSS file
                                if item not in css_files:
                                    css_files.append(item)
                                    css_path = os.path.join(temp_dir, item.file_name)
                                    os.makedirs(os.path.dirname(css_path), exist_ok=True)
                                    with open(css_path, 'w', encoding='utf-8') as f:
                                        f.write(item.get_content().decode('utf-8'))
                                html_content += f'<link rel="stylesheet" type="text/css" href="{item.file_name}">'
    
    # Add all collected styles
    for style in all_styles:
        html_content += style
    
    # Add basic CSS for common classes if no CSS files found
    if not css_files and not all_styles:
        basic_css = """
        <style>
        .chtitle { font-weight: bold; font-size: 1.2em; margin: 1em 0; }
        .chtitle-2 { font-weight: bold; font-size: 1.1em; margin: 0.8em 0; }
        .chtitle-3 { font-weight: bold; font-size: 1em; margin: 0.6em 0; }
        .chnum { font-weight: bold; }
        .indent { text-indent: 2em; }
        .noindent { text-indent: 0; }
        .box { border: 1px solid #ccc; padding: 1em; margin: 1em 0; }
        .box-text { margin: 0.5em 0; }
        .pageid { color: #666; font-size: 0.9em; }
        .imagef { text-align: center; margin: 1em 0; }
        .copy1, .copytt, .copy1aa, .copya, .copyt { font-family: monospace; }
        .dedt { font-style: italic; }
        .appleft { text-align: left; }
        .num { font-weight: bold; }
        .bmtitle { font-weight: bold; font-size: 1.1em; }
        .note { font-style: italic; background-color: #f9f9f9; padding: 0.5em; }
        .index1, .index2 { margin-left: 1em; }
        .border1 { border: 1px solid #000; }
        </style>
        """
        html_content += basic_css
    
    html_content += '</head><body>'
    
    for html_item in html_files:
        soup = BeautifulSoup(html_item.get_content(), 'html.parser')
        # Extract body content or the whole document
        body = soup.find('body')
        if body:
            html_content += str(body)
        else:
            html_content += str(soup)
    
    html_content += '</body></html>'
    
    # Write combined HTML to file
    html_file_path = os.path.join(temp_dir, 'book.html')
    with open(html_file_path, 'w', encoding='utf-8') as f:
        f.write(html_content)
    
    return html_file_path


def epub_to_pdf(epub_path, pdf_path):
    with tempfile.TemporaryDirectory() as temp_dir:
        html_file = extract_epub_to_html(epub_path, temp_dir)
        # WeasyPrint needs a base_url for images/styles
        HTML(html_file, base_url=temp_dir).write_pdf(pdf_path)


def main():
    if len(sys.argv) != 3:
        print('Usage: python epub_to_pdf.py input.epub output.pdf')
        sys.exit(1)
    epub_path = sys.argv[1]
    pdf_path = sys.argv[2]
    epub_to_pdf(epub_path, pdf_path)
    print(f'Converted {epub_path} to {pdf_path}')


if __name__ == '__main__':
    main() 