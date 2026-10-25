/**
 * The chosen segment of a switch (SegmentedControl and the rows built like it): lifted off its
 * grey track. In the light theme the page colour is lighter than the track; in the dark theme it
 * is darker, so the segment takes the accent with an edge instead of sinking into the track.
 * Segments carry a transparent border, so the edge does not move the label.
 */
export const SEGMENT_CHOSEN =
  'bg-background text-foreground shadow-sm dark:border-border dark:bg-accent'
