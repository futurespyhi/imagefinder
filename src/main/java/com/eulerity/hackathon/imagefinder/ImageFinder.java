package com.eulerity.hackathon.imagefinder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

@WebServlet(
    name = "ImageFinder",
    urlPatterns = {"/main"}
)
public class ImageFinder extends HttpServlet{
	private Set<String> visitedUrls ;
	private Set<String> allImageUrls;
	private static final long serialVersionUID = 1L;
	private static final int MAX_DEPTH = 3;
	private static final int THREAD_POOL_SIZE = 10;

	// Thread pool
	private Phaser phaser;
	/**
	 * Initialization
	 */
	public ImageFinder() {
		visitedUrls = Collections.synchronizedSet(new HashSet<>());
		allImageUrls = Collections.synchronizedSet(new HashSet<>());
	}

	protected static final Gson GSON = new GsonBuilder().create();

	/**
	 * Handles HTTP POST requests to extract image URLs from a given URL and its sub-pages.
	 * @param req The HttpServletRequest object containing the client's request
	 * @param resp The HttpServletResponse object used to send the response to the client
	 * @throws IOException If an I/O error occurs during the request processing
	 */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");
		String url = req.getParameter("url");
		System.out.println("Got request with URL: " + url);

		if (url == null || url.isEmpty()) {
			resp.getWriter().print(GSON.toJson(Collections.singletonList("Error: URL is empty")));
			return;
		}

		try {
			new URL(url);
			visitedUrls.clear(); // Clear visited URLs for each request
			allImageUrls.clear(); // Clear image URLs for each request
			crawlWithThreads(url); // Use multi-threaded crawling

			if (allImageUrls.isEmpty()) {
				resp.getWriter().print(GSON.toJson(Collections.singletonList("No images found on the webpage.")));
			} else {
				resp.getWriter().print(GSON.toJson(allImageUrls));
			}
		} catch (MalformedURLException e) {
			resp.getWriter().print(GSON.toJson(Collections.singletonList("Error: Invalid URL format.")));
		} catch (IOException e) {
			e.printStackTrace();
			resp.getWriter().print(GSON.toJson(Collections.singletonList("Error: Unable to fetch the webpage. Please check the URL and try again.")));
		}
    }

	/**
	 * Extracts image URLs from the given URL.
	 *
	 * @param url The URL of the webpage.
	 * @throws IOException If an I/O error occurs.
	 */
	private void crawlWithThreads(String url) throws IOException {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
		crawlUrl(url, 1, executor);

		executor.shutdown();
		try {
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			System.out.println("Executor interrupted: " + e.getMessage());
		}
	}

	private void crawlUrl(String url, int depth, ExecutorService executor) throws IOException {
		if (depth > MAX_DEPTH || visitedUrls.contains(url)) {
			System.out.println("Skipping URL: " + url + " (depth limit or already visited)");
			return;
		}
		visitedUrls.add(url);
		System.out.println("Crawling URL: " + url + " (Depth: " + depth + ")");

		try {
			if (!isValidUrl(url)) {
				System.out.println("Invalid URL: " + url);
				return;
			}

			Connection connection = Jsoup.connect(url);
			connection.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
			Document doc = connection.get();
			Elements images = doc.select("img");
			Elements links = doc.select("a[href]");

			for (Element image : images) {
				String src = image.attr("abs:src");
				if (!src.isEmpty()) {
					allImageUrls.add(src);
				}
			}

			String baseUrl = new URL(url).getHost();
			for (Element link : links) {
				String href = link.attr("abs:href");
				if (href != null && !href.isEmpty()) {
					try {
						URL linkUrl = new URL(href);
						if (linkUrl.getHost().equals(baseUrl)) {
							executor.submit(() -> {
								try {
									crawlUrl(href, depth + 1, executor);
								} catch (IOException e) {
									System.out.println("Error crawling " + href + ": " + e.getMessage());
								}
							});
						} else {
							System.out.println("Skipping external URL: " + href);
						}
					} catch (MalformedURLException e) {
						System.out.println("Malformed URL: " + href);
						e.printStackTrace();
					}
				}
			}
		} catch (HttpStatusException e) {
			if (e.getStatusCode() == 404) {
				System.out.println("404 Error: " + e.getUrl());
				return;
			} else {
				System.out.println("HTTP Error: " + e.getStatusCode() + " for " + e.getUrl());
				return;
			}
		} catch (IOException e) {
			System.out.println("IO Error for URL: " + url + " - " + e.getMessage());
			e.printStackTrace();
		}
	}

	private boolean isValidUrl(String url) {
		try {
			URL u = new URL(url);
			HttpURLConnection huc = (HttpURLConnection) u.openConnection();
			huc.setRequestMethod("HEAD");
			huc.setInstanceFollowRedirects(true); // enable automatic redirection
			huc.connect();
			int code = huc.getResponseCode();
			return code == HttpURLConnection.HTTP_OK;
		} catch (MalformedURLException e) {
			System.out.println("MalformedURLException: " + url);
			return false;
		} catch (IOException e) {
			System.out.println("IOException: " + url);
			return false;
		} catch (Exception e) {
			System.out.println("Exception: " + url);
			return false;
		}
	}

}
