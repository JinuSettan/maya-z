import { Router, type IRouter } from "express";
import healthRouter from "./health";
import videoGenerationRouter from "./video-generation";

const router: IRouter = Router();

router.use(healthRouter);
router.use(videoGenerationRouter);

export default router;
