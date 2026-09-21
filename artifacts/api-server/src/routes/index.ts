import { Router, type IRouter } from "express";
import healthRouter from "./health";
import videoGenerationRouter from "./video-generation";
import chatRouter from "./chat";

const router: IRouter = Router();

router.use(healthRouter);
router.use(videoGenerationRouter);
router.use(chatRouter);

export default router;
