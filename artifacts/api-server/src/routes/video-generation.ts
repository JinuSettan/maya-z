import { Router } from "express";
import { Client, handle_file } from "@gradio/client";

const router = Router();

router.post("/video/generate", async (req, res) => {
  try {
    const { prompt, imagePath } = req.body;

    if (!prompt || typeof prompt !== "string") {
      return res.status(400).json({
        error: "A video prompt is required",
      });
    }

    if (!imagePath || typeof imagePath !== "string") {
      return res.status(400).json({
        error: "An image path is required",
      });
    }

    const app = await Client.connect(
      "zerogpu-aoti/wan2-2-fp8da-aoti-faster"
    );

    const result = await app.predict("/generate_video", [
      await handle_file(imagePath),
      prompt,
      4,
      "",
      4,
      5,
      5,
      42,
      true,
    ]);

    return res.json({
      status: "completed",
      prompt,
      video: result,
    });
  } catch (error) {
    console.error("MAYA video generation error:", error);

    return res.status(500).json({
      error: "Video generation failed",
      details:
        error instanceof Error ? error.message : "Unknown error",
    });
  }
});

export default router;